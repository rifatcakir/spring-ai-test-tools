package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * HTTP access to one namespace on a Cassette Server ({@code docs/SERVER-PRD.md}), shared
 * by the four remote fixture stores.
 *
 * <p>Built on the JDK's own {@link HttpClient} rather than Spring's {@code RestClient} or
 * any HTTP library: this library usually sits on a test classpath, and the remote store
 * must not add a runtime dependency to every consumer to support a feature most of them
 * never switch on (PRD 5.2). HTTP/1.1 is pinned so the client never attempts an {@code
 * h2c} upgrade against a server that does not offer it.
 *
 * <p>Status handling encodes the failure semantics of PRD 5.3: {@code 404} is a miss,
 * {@code 409} on a write means another instance recorded the same request first, and
 * every other non-success status, every connection failure and every timeout is a
 * {@link CassetteServerException}, never a miss (fork a).
 *
 * @author Rifat Cakir
 */
public class CassetteServerClient {

	private static final Logger logger = LoggerFactory.getLogger(CassetteServerClient.class);

	private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

	private static final int MAX_ERROR_BODY = 300;

	private final String baseUrl;

	private final String namespace;

	private final @Nullable String token;

	private final Duration readTimeout;

	private final HttpClient httpClient;

	/**
	 * @param baseUrl the server's root URL, e.g. {@code http://cassette-server:9090}
	 * @param namespace the namespace this application's fixtures live in
	 * @param token bearer token for the client API, or {@code null} when the server needs
	 * none
	 * @param connectTimeout how long to wait for a connection
	 * @param readTimeout how long to wait for each response
	 */
	public CassetteServerClient(String baseUrl, String namespace, @Nullable String token, Duration connectTimeout,
			Duration readTimeout) {
		Assert.hasText(baseUrl, "baseUrl must not be empty");
		Assert.isTrue(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"),
				() -> "Cassette Server url must start with http:// or https://, got '" + baseUrl + "'");
		Assert.hasText(namespace, "namespace must not be empty");
		Assert.isTrue(NAMESPACE.matcher(namespace).matches(), () -> "invalid Cassette Server namespace '" + namespace
				+ "': use letters, digits, '.', '_' and '-', starting with a letter or digit");
		Assert.notNull(connectTimeout, "connectTimeout must not be null");
		Assert.notNull(readTimeout, "readTimeout must not be null");
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.namespace = namespace;
		this.token = StringUtils.hasText(token) ? token : null;
		this.readTimeout = readTimeout;
		this.httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	public String getBaseUrl() {
		return this.baseUrl;
	}

	public String getNamespace() {
		return this.namespace;
	}

	/**
	 * The URL of one fixture: what log lines and {@code VcrCacheMissException} messages
	 * show, and something a person can paste into {@code curl} to look at it.
	 */
	public String locationOf(Kind kind, String hash) {
		return this.baseUrl + "/api/v1/ns/" + this.namespace + "/" + kind.pathSegment() + "/" + hash;
	}

	/**
	 * A short name for {@code CACHE HIT} log lines: {@code namespace/kind/hash}.
	 */
	public String fixtureNameOf(Kind kind, String hash) {
		return this.namespace + "/" + kind.pathSegment() + "/" + hash;
	}

	/**
	 * The stored document, or empty when the server has none ({@code 404}).
	 * @throws CassetteServerException for anything other than {@code 200} or {@code 404}
	 */
	public Optional<String> get(Kind kind, String hash) {
		HttpRequest request = request(kind, hash, false).header("Accept", "application/json").GET().build();
		HttpResponse<String> response = send(request, kind, hash);
		return switch (response.statusCode()) {
			case 200 -> Optional.of(response.body());
			case 404 -> Optional.empty();
			default -> throw failure("GET", kind, hash, response);
		};
	}

	/**
	 * Store a document.
	 * @param overwrite replace an existing document; only {@code RECORD_ALWAYS} asks for
	 * this, the one mode where the last write wins (PRD 5.3)
	 * @return what the server did with it
	 * @throws CassetteServerException when the server refuses the document or fails
	 */
	public WriteOutcome put(Kind kind, String hash, String document, boolean overwrite) {
		Assert.notNull(document, "document must not be null");
		HttpRequest request = request(kind, hash, overwrite).header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString(document, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response = send(request, kind, hash);
		return switch (response.statusCode()) {
			case 200 -> WriteOutcome.REPLACED;
			case 201 -> WriteOutcome.STORED;
			case 409 -> WriteOutcome.KEPT_EXISTING;
			default -> throw failure("PUT", kind, hash, response);
		};
	}

	private HttpRequest.Builder request(Kind kind, String hash, boolean overwrite) {
		Assert.notNull(kind, "kind must not be null");
		Assert.hasText(hash, "hash must not be empty");
		String url = locationOf(kind, hash) + (overwrite ? "?overwrite=true" : "");
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(this.readTimeout);
		if (this.token != null) {
			builder.header("Authorization", "Bearer " + this.token);
		}
		return builder;
	}

	private HttpResponse<String> send(HttpRequest request, Kind kind, String hash) {
		try {
			HttpResponse<String> response = this.httpClient.send(request,
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			logger.debug("VCR REMOTE {} {} -> {}", request.method(), request.uri(), response.statusCode());
			return response;
		}
		catch (HttpTimeoutException ex) {
			throw new CassetteServerException(describe(request.method(), kind, hash) + " timed out. " + consequence(),
					ex);
		}
		catch (IOException ex) {
			throw new CassetteServerException(describe(request.method(), kind, hash) + " failed: the server at "
					+ this.baseUrl + " is unreachable (" + ex + "). " + consequence(), ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new CassetteServerException(describe(request.method(), kind, hash) + " was interrupted", ex);
		}
	}

	private CassetteServerException failure(String method, Kind kind, String hash, HttpResponse<String> response) {
		int status = response.statusCode();
		String hint = switch (status) {
			case 401, 403 -> " Check spring.ai.test.vcr.remote.token.";
			case 400 -> " The server refused the fixture; see its reason above.";
			default -> "";
		};
		return new CassetteServerException(describe(method, kind, hash) + " answered HTTP " + status + ": "
				+ abbreviate(response.body()) + "." + hint + " " + consequence());
	}

	private String describe(String method, Kind kind, String hash) {
		return "Cassette Server " + method + " " + locationOf(kind, hash) + " (namespace '" + this.namespace + "')";
	}

	private static String consequence() {
		return "Not calling the real model and not falling back to local fixtures: with store=remote an "
				+ "unavailable server fails the call.";
	}

	private static String abbreviate(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return "(empty body)";
		}
		String flat = body.strip().replaceAll("\\s+", " ");
		return (flat.length() <= MAX_ERROR_BODY) ? flat : flat.substring(0, MAX_ERROR_BODY) + "…";
	}

	/**
	 * The fixture families, with the path segment the server files each under.
	 */
	public enum Kind {

		CHAT("chat"), STREAM("stream"), EMBEDDING("embedding"), TOOL("tool");

		private final String pathSegment;

		Kind(String pathSegment) {
			this.pathSegment = pathSegment;
		}

		public String pathSegment() {
			return this.pathSegment;
		}

	}

	/**
	 * What the server did with a {@link #put}.
	 */
	public enum WriteOutcome {

		/** Newly stored ({@code 201}). */
		STORED,

		/** Replaced an existing document; only with {@code overwrite} ({@code 200}). */
		REPLACED,

		/** Another client stored one first and the server kept it ({@code 409}). */
		KEPT_EXISTING

	}

}
