package io.github.rifatcakir.springai.testtools.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 6 over real HTTP: namespace default mode, the misses log and the export.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, args = "--admin-token=admin-secret")
class NamespaceAdminTests {

	@TempDir
	static Path storageDir;

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		registry.add("cassette.storage-dir", () -> storageDir.toString());
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	private HttpTestClient client;

	private HttpTestClient admin;

	@BeforeEach
	void clients() {
		this.client = new HttpTestClient(this.port);
		this.admin = this.client.withToken("admin-secret");
	}

	@Test
	void aNamespaceWithoutItsOwnModeReportsTheServerDefault() {
		Map<String, Object> config = json(this.client.get("/api/v1/ns/fresh-ns/config"));

		assertThat(config).containsEntry("namespace", "fresh-ns")
			.containsEntry("mode", "RECORD_OR_REPLAY")
			.containsEntry("source", "server-default");
	}

	@Test
	void anAdminSetsAndClearsANamespaceDefault() {
		HttpResponse<byte[]> set = this.admin.put("/__admin/namespaces/qa-env/mode",
				"{\"mode\":\"replay_only\"}".getBytes(StandardCharsets.UTF_8));
		assertThat(set.statusCode()).isEqualTo(200);

		assertThat(json(this.client.get("/api/v1/ns/qa-env/config"))).containsEntry("mode", "REPLAY_ONLY")
			.containsEntry("source", "namespace");
		List<Map<String, Object>> namespaces = jsonList(this.admin.get("/__admin/namespaces"));
		assertThat(namespaces).anySatisfy(entry -> assertThat(entry).containsEntry("namespace", "qa-env")
			.containsEntry("mode", "REPLAY_ONLY")
			.containsEntry("modeSource", "namespace"));

		assertThat(this.admin.delete("/__admin/namespaces/qa-env/mode").statusCode()).isEqualTo(200);
		assertThat(json(this.client.get("/api/v1/ns/qa-env/config"))).containsEntry("mode", "RECORD_OR_REPLAY")
			.containsEntry("source", "server-default");
	}

	@Test
	void anUnknownModeIs400AndChangesNothing() {
		assertThat(this.admin.put("/__admin/namespaces/typo-ns/mode", "{\"mode\":\"REPLAY_SOMETIMES\"}"
			.getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
		assertThat(this.admin.put("/__admin/namespaces/typo-ns/mode", "{}".getBytes(StandardCharsets.UTF_8))
			.statusCode()).isEqualTo(400);
		assertThat(json(this.client.get("/api/v1/ns/typo-ns/config"))).containsEntry("source", "server-default");
	}

	@Test
	void changingTheModeNeedsTheAdminToken() {
		assertThat(this.client.put("/__admin/namespaces/locked/mode", "{\"mode\":\"BYPASS\"}"
			.getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(401);
		assertThat(json(this.client.get("/api/v1/ns/locked/config"))).containsEntry("source", "server-default");
	}

	@Test
	void misses404sAreListedNewestFirstAndShowWhetherTheyWereRecordedSince() {
		String first = TestDocuments.hash("miss-1");
		String second = TestDocuments.hash("miss-2");
		this.client.get("/api/v1/ns/miss-ns/chat/" + first);
		this.client.get("/api/v1/ns/miss-ns/embedding/" + second);
		this.client.put("/api/v1/ns/miss-ns/chat/" + first, TestDocuments.chat(first));

		List<Map<String, Object>> misses = jsonList(this.admin.get("/__admin/namespaces/miss-ns/misses"));

		assertThat(misses).extracting(miss -> miss.get("hash")).containsExactly(second, first);
		assertThat(misses.get(0)).containsEntry("kind", "embedding").containsEntry("recordedSince", false);
		assertThat(misses.get(1)).containsEntry("kind", "chat").containsEntry("recordedSince", true);
		assertThat((String) misses.get(0).get("at")).isNotBlank();
	}

	@Test
	void hitsAreNotMisses() {
		String hash = TestDocuments.hash("hit-only");
		this.client.put("/api/v1/ns/hit-ns/tool/" + hash, TestDocuments.tool(hash));
		this.client.get("/api/v1/ns/hit-ns/tool/" + hash);

		assertThat(jsonList(this.admin.get("/__admin/namespaces/hit-ns/misses"))).isEmpty();
	}

	@Test
	void exportIsAZipInTheLibrarysLayoutWithTheStoredBytes() throws IOException {
		Map<String, byte[]> expected = new LinkedHashMap<>();
		for (FixtureKind kind : FixtureKind.values()) {
			String hash = TestDocuments.hash("export-" + kind);
			byte[] document = TestDocuments.of(kind, hash);
			this.client.put("/api/v1/ns/export-ns/" + kind.pathSegment() + "/" + hash, document);
			expected.put(kind.directory() + "/" + hash + ".json", document);
		}

		HttpResponse<byte[]> export = this.admin.get("/__admin/namespaces/export-ns/export");

		assertThat(export.statusCode()).isEqualTo(200);
		assertThat(export.headers().firstValue("Content-Type")).hasValue("application/zip");
		assertThat(export.headers().firstValue("Content-Disposition"))
			.hasValue("attachment; filename=\"export-ns-fixtures.zip\"");
		Map<String, byte[]> entries = unzip(export.body());
		assertThat(entries.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
		expected.forEach((name, bytes) -> assertThat(entries.get(name)).as(name).isEqualTo(bytes));
	}

	@Test
	void exportingAnUnchangedNamespaceTwiceGivesTheSameBytes() {
		String hash = TestDocuments.hash("stable-export");
		this.client.put("/api/v1/ns/stable-ns/chat/" + hash, TestDocuments.chat(hash));

		HttpResponse<byte[]> first = this.admin.get("/__admin/namespaces/stable-ns/export");
		HttpResponse<byte[]> second = this.admin.get("/__admin/namespaces/stable-ns/export");

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(second.body()).isEqualTo(first.body());
	}

	@Test
	void exportNeedsTheAdminTokenAndAnExistingNamespace() {
		assertThat(this.client.get("/__admin/namespaces/export-ns/export").statusCode()).isEqualTo(401);
		assertThat(this.admin.get("/__admin/namespaces/no-such-ns/export").statusCode()).isEqualTo(404);
	}

	static Map<String, byte[]> unzip(byte[] zip) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				entries.put(entry.getName(), in.readAllBytes());
			}
		}
		return entries;
	}

	private static Map<String, Object> json(HttpResponse<byte[]> response) {
		assertThat(response.statusCode()).isEqualTo(200);
		return JSON.readValue(response.body(), new TypeReference<Map<String, Object>>() {
		});
	}

	private static List<Map<String, Object>> jsonList(HttpResponse<byte[]> response) {
		assertThat(response.statusCode()).isEqualTo(200);
		return JSON.readValue(response.body(), new TypeReference<List<Map<String, Object>>>() {
		});
	}

}
