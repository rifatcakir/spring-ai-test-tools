package io.github.rifatcakir.springai.testtools.server;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, args = "--admin-token=admin-secret")
class AdminApiTests {

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
	void listsNamespacesWithCountsPerKind() {
		for (FixtureKind kind : FixtureKind.values()) {
			String hash = TestDocuments.hash("admin-ns-" + kind);
			this.client.put("/api/v1/ns/admin-ns/" + kind.pathSegment() + "/" + hash, TestDocuments.of(kind, hash));
		}

		List<Map<String, Object>> namespaces = json(this.admin.get("/__admin/namespaces"));

		assertThat(namespaces).anySatisfy(namespace -> {
			assertThat(namespace).containsEntry("namespace", "admin-ns");
			assertThat(namespace.get("fixtures"))
				.isEqualTo(Map.of("chat", 1, "stream", 1, "embedding", 1, "tool", 1));
		});
	}

	@Test
	void listsFixturesFilteredByKind() {
		String chat = TestDocuments.hash("admin-list-chat");
		String tool = TestDocuments.hash("admin-list-tool");
		this.client.put("/api/v1/ns/admin-list/chat/" + chat, TestDocuments.chat(chat));
		this.client.put("/api/v1/ns/admin-list/tool/" + tool, TestDocuments.tool(tool));

		List<Map<String, Object>> chats = json(this.admin.get("/__admin/namespaces/admin-list/fixtures?kind=chat"));
		List<Map<String, Object>> all = json(this.admin.get("/__admin/namespaces/admin-list/fixtures"));

		assertThat(chats).singleElement().satisfies(entry -> {
			assertThat(entry).containsEntry("hash", chat).containsEntry("kind", "chat");
			assertThat(entry).containsEntry("model", "llama3.2:1b").containsEntry("summary", "Capital of France?");
			assertThat(entry).containsEntry("recordedAt", "2026-10-10T00:00:00Z");
		});
		assertThat(all).extracting(entry -> entry.get("kind")).containsExactly("chat", "tool");
		assertThat(this.admin.get("/__admin/namespaces/admin-list/fixtures?kind=movie").statusCode()).isEqualTo(400);
	}

	@Test
	void getsOneFixture() {
		String hash = TestDocuments.hash("admin-get");
		this.client.put("/api/v1/ns/admin-get/embedding/" + hash, TestDocuments.embedding(hash));

		HttpResponse<byte[]> fixture = this.admin.get("/__admin/namespaces/admin-get/fixtures/embedding/" + hash);

		assertThat(fixture.statusCode()).isEqualTo(200);
		assertThat(fixture.body()).isEqualTo(TestDocuments.embedding(hash));
	}

	@Test
	void deletingAFixtureMakesTheNextClientLookupMiss() {
		String hash = TestDocuments.hash("admin-delete");
		this.client.put("/api/v1/ns/admin-delete/chat/" + hash, TestDocuments.chat(hash));

		assertThat(this.admin.delete("/__admin/namespaces/admin-delete/fixtures/chat/" + hash).statusCode())
			.isEqualTo(204);

		assertThat(this.client.get("/api/v1/ns/admin-delete/chat/" + hash).statusCode()).isEqualTo(404);
		assertThat(this.admin.delete("/__admin/namespaces/admin-delete/fixtures/chat/" + hash).statusCode())
			.isEqualTo(404);
	}

	@Test
	void deleteNeedsTheAdminToken() {
		String hash = TestDocuments.hash("admin-delete-token");
		this.client.put("/api/v1/ns/admin-delete/chat/" + hash, TestDocuments.chat(hash));

		assertThat(this.client.delete("/__admin/namespaces/admin-delete/fixtures/chat/" + hash).statusCode())
			.isEqualTo(401);
		assertThat(this.client.get("/api/v1/ns/admin-delete/chat/" + hash).statusCode()).isEqualTo(200);
	}

	private static <T> T json(HttpResponse<byte[]> response) {
		assertThat(response.statusCode()).isEqualTo(200);
		return JSON.readValue(response.body(), new TypeReference<T>() {
		});
	}

}
