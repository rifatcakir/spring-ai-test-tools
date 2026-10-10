package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An in-memory stand-in for the Cassette Server's client API, on the JDK's built-in HTTP
 * server so the library's tests need no new dependency.
 *
 * <p>Implements just the semantics the remote stores rely on ({@code 200}/{@code 404} on
 * {@code GET}; {@code 201}/{@code 409}, or {@code 200}/{@code 201} with {@code
 * ?overwrite=true}, on {@code PUT}) and records every request, so tests can assert how
 * many lookups and writes reached the server, not just what came back. The real server's
 * own behavior is tested in its module; the fixture contract keeps the two in step.
 */
public final class StubCassetteServer implements AutoCloseable {

	private final HttpServer server;

	private final Map<String, String> documents = new ConcurrentHashMap<>();

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private volatile Integer forcedStatus;

	private volatile Duration delay = Duration.ZERO;

	private final Map<String, String> namespaceModes = new ConcurrentHashMap<>();

	private StubCassetteServer(HttpServer server) {
		this.server = server;
	}

	public static StubCassetteServer start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			StubCassetteServer stub = new StubCassetteServer(server);
			server.createContext("/api/v1/ns/", stub::handle);
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();
			return stub;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	public CassetteServerClient client(String namespace) {
		return client(namespace, null, Duration.ofSeconds(5));
	}

	public CassetteServerClient client(String namespace, String token, Duration readTimeout) {
		return new CassetteServerClient(url(), namespace, token, Duration.ofSeconds(2), readTimeout);
	}

	/** Answer every request with this status (and an error body) until reset. */
	public void failWith(int status) {
		this.forcedStatus = status;
	}

	public void delayResponses(Duration delay) {
		this.delay = delay;
	}

	/** Set a namespace's default mode, as the admin API would; unset means RECORD_OR_REPLAY. */
	public void namespaceMode(String namespace, String mode) {
		this.namespaceModes.put(namespace, mode);
	}

	/** Lookups and writes of fixtures, leaving out {@code /config} requests. */
	public long countFixtureRequests(String method) {
		return this.requests.stream()
			.filter(request -> request.method().equals(method) && !request.path().endsWith("/config"))
			.count();
	}

	/** Pre-store a document, as if another instance had recorded it. */
	public void store(String namespace, String kind, String hash, String document) {
		this.documents.put(key(namespace, kind, hash), document);
	}

	public String document(String namespace, String kind, String hash) {
		return this.documents.get(key(namespace, kind, hash));
	}

	public List<Request> requests() {
		return List.copyOf(this.requests);
	}

	public long count(String method) {
		return this.requests.stream().filter(request -> request.method().equals(method)).count();
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private static String key(String namespace, String kind, String hash) {
		return namespace + "/" + kind + "/" + hash;
	}

	private void handle(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		String path = exchange.getRequestURI().getPath().substring("/api/v1/ns/".length());
		String query = exchange.getRequestURI().getQuery();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		this.requests.add(new Request(method, path, query, exchange.getRequestHeaders().getFirst("Authorization"),
				body));
		try {
			if (!this.delay.isZero()) {
				Thread.sleep(this.delay.toMillis());
			}
			if (this.forcedStatus != null) {
				respond(exchange, this.forcedStatus, "{\"error\":\"forced by test\"}");
				return;
			}
			if (method.equals("GET") && path.endsWith("/config")) {
				String namespace = path.substring(0, path.length() - "/config".length());
				respond(exchange, 200, "{\"namespace\":\"" + namespace + "\",\"mode\":\""
						+ this.namespaceModes.getOrDefault(namespace, "RECORD_OR_REPLAY") + "\"}");
			}
			else if (method.equals("GET")) {
				String document = this.documents.get(path);
				respond(exchange, (document != null) ? 200 : 404, (document != null) ? document : "{\"error\":\"none\"}");
			}
			else if (method.equals("PUT")) {
				boolean overwrite = "overwrite=true".equals(query);
				String existing = this.documents.get(path);
				if (existing != null && !overwrite) {
					respond(exchange, 409, existing);
					return;
				}
				this.documents.put(path, body);
				respond(exchange, (existing != null) ? 200 : 201, "");
			}
			else {
				respond(exchange, 405, "");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, (bytes.length == 0) ? -1 : bytes.length);
		if (bytes.length > 0) {
			exchange.getResponseBody().write(bytes);
		}
		exchange.close();
	}

	/**
	 * One request as the stub received it.
	 * @param path {@code namespace/kind/hash}
	 */
	public record Request(String method, String path, String query, String authorization, String body) {
	}

}
