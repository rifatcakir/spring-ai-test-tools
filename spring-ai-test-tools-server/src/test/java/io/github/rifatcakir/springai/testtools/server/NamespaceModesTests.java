package io.github.rifatcakir.springai.testtools.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceModesTests {

	@TempDir
	Path root;

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void aSetModeSurvivesARestart() {
		new NamespaceModes(this.root, this.mapper, NamespaceModes.Mode.RECORD_OR_REPLAY).set("qa",
				NamespaceModes.Mode.REPLAY_ONLY);

		NamespaceModes restarted = new NamespaceModes(this.root, this.mapper, NamespaceModes.Mode.RECORD_OR_REPLAY);

		assertThat(restarted.effective("qa")).isEqualTo(NamespaceModes.Mode.REPLAY_ONLY);
		assertThat(restarted.effective("other")).isEqualTo(NamespaceModes.Mode.RECORD_OR_REPLAY);
	}

	@Test
	void theSettingsFileLivesOutsideEveryNamespaceAndIsNeverListedAsOne() {
		JsonMapper jsonMapper = JsonMapper.builder().build();
		new NamespaceModes(this.root, jsonMapper, NamespaceModes.Mode.RECORD_OR_REPLAY).set("qa",
				NamespaceModes.Mode.BYPASS);

		assertThat(this.root.resolve(".cassette-server/namespace-modes.json")).exists();
		assertThat(new FixtureStorage(this.root, new FixtureValidator(jsonMapper), jsonMapper).namespaces()).isEmpty();
	}

	@Test
	void aMangledSettingsFileFallsBackToTheServerDefaultInsteadOfFailingStartup() throws Exception {
		Files.createDirectories(this.root.resolve(".cassette-server"));
		Files.writeString(this.root.resolve(".cassette-server/namespace-modes.json"), "{ not json");

		NamespaceModes modes = new NamespaceModes(this.root, this.mapper, NamespaceModes.Mode.REPLAY_ONLY);

		assertThat(modes.effective("qa")).isEqualTo(NamespaceModes.Mode.REPLAY_ONLY);
	}

	@Test
	void missLogIsBoundedAndNewestFirst() {
		MissLog log = new MissLog(2, Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC));
		log.record("svc", FixtureKind.CHAT, "a");
		log.record("svc", FixtureKind.CHAT, "b");
		log.record("svc", FixtureKind.TOOL, "c");

		assertThat(log.recent("svc")).extracting(MissLog.Miss::hash).containsExactly("c", "b");
		assertThat(log.recent("elsewhere")).isEmpty();
	}

}
