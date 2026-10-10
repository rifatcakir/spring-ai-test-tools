package io.github.rifatcakir.springai.testtools.server;

import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.Assert;

/**
 * Checks the three things the server is allowed to know about a fixture: that it is a
 * JSON object of the right {@link FixtureKind}, that its own {@code hash} matches the one
 * it is filed under, and that its {@code schemaVersion} is one this server knows.
 *
 * <p>Nothing else is interpreted. The request and response content belong to the client
 * (PRD fork d); the server never needs to understand a prompt to store one, and every
 * field it does not check is a field the library can evolve without a server release.
 *
 * <p>Also where URL path input is checked before it gets anywhere near a file path. A
 * hash is exactly 64 lowercase hex characters (what {@code VcrCacheKeyGenerator} emits)
 * and a namespace starts with a letter or digit, so neither can contain {@code ..} or a
 * separator.
 *
 * @author Rifat Cakir
 */
public class FixtureValidator {

	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

	private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

	private static final Pattern SCHEMA_VERSION = Pattern.compile("[1-9][0-9]{0,5}");

	private final JsonMapper jsonMapper;

	public FixtureValidator(JsonMapper jsonMapper) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		this.jsonMapper = jsonMapper;
	}

	public static boolean isValidHash(String hash) {
		return hash != null && HASH.matcher(hash).matches();
	}

	public static boolean isValidNamespace(String namespace) {
		return namespace != null && NAMESPACE.matcher(namespace).matches();
	}

	/**
	 * Validate a document submitted for {@code (kind, hash)}.
	 * @return the parsed document
	 * @throws InvalidFixtureException describing the first problem found
	 */
	public JsonNode validate(FixtureKind kind, String hash, byte[] body) {
		Assert.notNull(kind, "kind must not be null");
		Assert.notNull(body, "body must not be null");
		JsonNode document = parse(body);
		if (document == null || !document.isObject()) {
			throw new InvalidFixtureException("body is not a JSON object");
		}
		JsonNode documentHash = document.path("hash");
		if (!documentHash.isString() || !documentHash.stringValue().equals(hash)) {
			throw new InvalidFixtureException("body hash " + describe(documentHash) + " does not match the path hash "
					+ hash);
		}
		FixtureKind detected = FixtureKind.detect(document);
		if (detected != kind) {
			throw new InvalidFixtureException("body is not a " + kind.pathSegment() + " fixture"
					+ ((detected != null) ? " (it looks like a " + detected.pathSegment() + " fixture)" : ""));
		}
		JsonNode version = document.path("schemaVersion");
		if (!version.isString() || !SCHEMA_VERSION.matcher(version.stringValue()).matches()) {
			throw new InvalidFixtureException("schemaVersion " + describe(version) + " is not a known version");
		}
		int number = Integer.parseInt(version.stringValue());
		if (number > kind.latestSchemaVersion()) {
			throw new InvalidFixtureException("schemaVersion " + number + " is newer than this server knows for "
					+ kind.pathSegment() + " fixtures (latest " + kind.latestSchemaVersion()
					+ "); upgrade the Cassette Server");
		}
		return document;
	}

	/**
	 * Whether stored bytes are a readable document of {@code kind} for {@code hash}. Used
	 * on reads, so a corrupt or foreign file degrades to "not found" instead of being
	 * served (design rule #7).
	 */
	public boolean isReadable(FixtureKind kind, String hash, byte[] stored) {
		try {
			validate(kind, hash, stored);
			return true;
		}
		catch (InvalidFixtureException ex) {
			return false;
		}
	}

	private JsonNode parse(byte[] body) {
		try {
			return this.jsonMapper.readTree(body);
		}
		catch (JacksonException ex) {
			throw new InvalidFixtureException("body is not well-formed JSON: " + ex.getOriginalMessage());
		}
	}

	private static String describe(JsonNode node) {
		return node.isMissingNode() ? "(missing)" : node.toString();
	}

	/**
	 * A submitted document the server refuses; mapped to {@code 400}.
	 */
	public static class InvalidFixtureException extends RuntimeException {

		public InvalidFixtureException(String message) {
			super(message);
		}

	}

}
