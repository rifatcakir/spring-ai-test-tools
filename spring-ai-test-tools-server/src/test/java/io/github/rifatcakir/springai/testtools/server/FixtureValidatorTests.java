package io.github.rifatcakir.springai.testtools.server;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureValidatorTests {

	private static final String HASH = TestDocuments.hash("validator");

	private final FixtureValidator validator = new FixtureValidator(JsonMapper.builder().build());

	@ParameterizedTest
	@EnumSource(FixtureKind.class)
	void acceptsAWellFormedDocumentOfEachKind(FixtureKind kind) {
		assertThat(this.validator.validate(kind, HASH, TestDocuments.of(kind, HASH)).path("hash").stringValue())
			.isEqualTo(HASH);
	}

	@Test
	void refusesABodyWhoseHashDoesNotMatchThePath() {
		assertThatThrownBy(() -> this.validator.validate(FixtureKind.CHAT, HASH, TestDocuments.chat(TestDocuments.hash("other"))))
			.isInstanceOf(FixtureValidator.InvalidFixtureException.class)
			.hasMessageContaining("does not match the path hash");
	}

	@Test
	void refusesADocumentOfAnotherKindAndSaysWhichKindItLooksLike() {
		assertThatThrownBy(() -> this.validator.validate(FixtureKind.CHAT, HASH, TestDocuments.stream(HASH)))
			.isInstanceOf(FixtureValidator.InvalidFixtureException.class)
			.hasMessageContaining("not a chat fixture")
			.hasMessageContaining("looks like a stream fixture");
	}

	@Test
	void refusesASchemaVersionNewerThanTheServerKnows() {
		byte[] future = new String(TestDocuments.chat(HASH), StandardCharsets.UTF_8)
			.replace("\"schemaVersion\" : \"6\"", "\"schemaVersion\" : \"7\"")
			.getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> this.validator.validate(FixtureKind.CHAT, HASH, future))
			.isInstanceOf(FixtureValidator.InvalidFixtureException.class)
			.hasMessageContaining("upgrade the Cassette Server");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "not json", "[1,2]", "{\"hash\":", "\"just a string\"" })
	void refusesBodiesThatAreNotAJsonObject(String body) {
		assertThatThrownBy(() -> this.validator.validate(FixtureKind.CHAT, HASH, body.getBytes(StandardCharsets.UTF_8)))
			.isInstanceOf(FixtureValidator.InvalidFixtureException.class);
	}

	@Test
	void refusesAMissingOrNonNumericSchemaVersion() {
		byte[] missing = new String(TestDocuments.tool(HASH), StandardCharsets.UTF_8)
			.replace("\"schemaVersion\" : \"1\",", "")
			.getBytes(StandardCharsets.UTF_8);
		byte[] text = new String(TestDocuments.tool(HASH), StandardCharsets.UTF_8)
			.replace("\"schemaVersion\" : \"1\"", "\"schemaVersion\" : \"v1\"")
			.getBytes(StandardCharsets.UTF_8);
		assertThatThrownBy(() -> this.validator.validate(FixtureKind.TOOL, HASH, missing))
			.hasMessageContaining("schemaVersion (missing)");
		assertThatThrownBy(() -> this.validator.validate(FixtureKind.TOOL, HASH, text))
			.hasMessageContaining("not a known version");
	}

	@ParameterizedTest
	@ValueSource(strings = { "..", "../etc", "a/b", ".hidden", "-dash", "" })
	void namespacesThatCouldEscapeTheStorageDirectoryAreInvalid(String namespace) {
		assertThat(FixtureValidator.isValidNamespace(namespace)).isFalse();
	}

	@Test
	void hashesMustBeLowercaseSha256Hex() {
		assertThat(FixtureValidator.isValidHash(HASH)).isTrue();
		assertThat(FixtureValidator.isValidHash(HASH.toUpperCase())).isFalse();
		assertThat(FixtureValidator.isValidHash(HASH.substring(1))).isFalse();
		assertThat(FixtureValidator.isValidHash("../" + HASH.substring(3))).isFalse();
	}

}
