package io.github.rifatcakir.springai.testtools.server;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientApiTests {

	@TempDir
	static Path storageDir;

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		registry.add("cassette.storage-dir", () -> storageDir.toString());
	}

	@LocalServerPort
	int port;

	private HttpTestClient http;

	@BeforeEach
	void client() {
		this.http = new HttpTestClient(this.port);
	}

	@Test
	void putThenGetReturnsTheSameBytesAndStoresThemInTheLibraryLayout() throws Exception {
		String hash = TestDocuments.hash("round-trip");
		byte[] document = TestDocuments.chat(hash);

		assertThat(this.http.put("/api/v1/ns/svc/chat/" + hash, document).statusCode()).isEqualTo(201);

		HttpResponse<byte[]> get = this.http.get("/api/v1/ns/svc/chat/" + hash);
		assertThat(get.statusCode()).isEqualTo(200);
		assertThat(get.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
		assertThat(get.body()).isEqualTo(document);
		assertThat(Files.readAllBytes(storageDir.resolve("svc/llm-cache/" + hash + ".json"))).isEqualTo(document);
	}

	@Test
	void everyKindRoundTrips() {
		for (FixtureKind kind : FixtureKind.values()) {
			String hash = TestDocuments.hash("kind-" + kind);
			byte[] document = TestDocuments.of(kind, hash);
			String path = "/api/v1/ns/kinds/" + kind.pathSegment() + "/" + hash;
			assertThat(this.http.put(path, document).statusCode()).as(kind.name()).isEqualTo(201);
			assertThat(this.http.get(path).body()).as(kind.name()).isEqualTo(document);
		}
	}

	@Test
	void aPutSentAsFormContentIsStillReadAsTheRawDocument() {
		// curl --data-binary without -H 'Content-Type: application/json' sends this.
		String hash = TestDocuments.hash("form-content");
		assertThat(this.http.put("/api/v1/ns/svc/tool/" + hash, TestDocuments.tool(hash),
				"application/x-www-form-urlencoded").statusCode()).isEqualTo(201);
		assertThat(this.http.get("/api/v1/ns/svc/tool/" + hash).body()).isEqualTo(TestDocuments.tool(hash));
	}

	@Test
	void unknownHashIs404() {
		HttpResponse<byte[]> get = this.http.get("/api/v1/ns/svc/chat/" + TestDocuments.hash("never-recorded"));
		assertThat(get.statusCode()).isEqualTo(404);
		assertThat(new String(get.body(), StandardCharsets.UTF_8)).contains("no chat fixture");
	}

	@Test
	void secondPutIs409WithTheExistingDocumentAndKeepsIt() {
		String hash = TestDocuments.hash("conflict");
		byte[] first = TestDocuments.chat(hash, "first");
		this.http.put("/api/v1/ns/svc/chat/" + hash, first);

		HttpResponse<byte[]> second = this.http.put("/api/v1/ns/svc/chat/" + hash, TestDocuments.chat(hash, "second"));

		assertThat(second.statusCode()).isEqualTo(409);
		assertThat(second.body()).isEqualTo(first);
		assertThat(this.http.get("/api/v1/ns/svc/chat/" + hash).body()).isEqualTo(first);
	}

	@Test
	void overwriteReplacesTheExistingDocument() {
		String hash = TestDocuments.hash("overwrite");
		this.http.put("/api/v1/ns/svc/chat/" + hash, TestDocuments.chat(hash, "first"));

		HttpResponse<byte[]> replaced = this.http.put("/api/v1/ns/svc/chat/" + hash + "?overwrite=true",
				TestDocuments.chat(hash, "second"));

		assertThat(replaced.statusCode()).isEqualTo(200);
		assertThat(this.http.get("/api/v1/ns/svc/chat/" + hash).body()).isEqualTo(TestDocuments.chat(hash, "second"));
		String fresh = TestDocuments.hash("overwrite-fresh");
		assertThat(this.http.put("/api/v1/ns/svc/chat/" + fresh + "?overwrite=true", TestDocuments.chat(fresh))
			.statusCode()).isEqualTo(201);
	}

	@Test
	void bodyHashThatDoesNotMatchThePathIs400AndStoresNothing() {
		String hash = TestDocuments.hash("mismatch");
		HttpResponse<byte[]> put = this.http.put("/api/v1/ns/svc/chat/" + hash,
				TestDocuments.chat(TestDocuments.hash("something else")));

		assertThat(put.statusCode()).isEqualTo(400);
		assertThat(new String(put.body(), StandardCharsets.UTF_8)).contains("does not match the path hash");
		assertThat(this.http.get("/api/v1/ns/svc/chat/" + hash).statusCode()).isEqualTo(404);
	}

	@Test
	void aDocumentOfTheWrongKindIs400() {
		String hash = TestDocuments.hash("wrong-kind");
		assertThat(this.http.put("/api/v1/ns/svc/chat/" + hash, TestDocuments.stream(hash)).statusCode()).isEqualTo(400);
	}

	@Test
	void invalidPathsAreRefusedBeforeTouchingTheDisk() {
		String hash = TestDocuments.hash("paths");
		assertThat(this.http.get("/api/v1/ns/svc/movie/" + hash).statusCode()).isEqualTo(404);
		assertThat(this.http.get("/api/v1/ns/svc/chat/" + hash.toUpperCase()).statusCode()).isEqualTo(400);
		assertThat(this.http.get("/api/v1/ns/.hidden/chat/" + hash).statusCode()).isEqualTo(400);
		assertThat(this.http.put("/api/v1/ns/svc/chat/not-a-hash", TestDocuments.chat("not-a-hash")).statusCode())
			.isEqualTo(400);
	}

	@Test
	void theClientApiIsOpenWhenNoClientTokenIsConfigured() {
		assertThat(this.http.get("/api/v1/ns/svc/chat/" + TestDocuments.hash("open")).statusCode()).isEqualTo(404);
	}

	@Test
	void healthIsUpWithoutAnyToken() {
		HttpResponse<byte[]> health = this.http.get("/actuator/health");
		assertThat(health.statusCode()).isEqualTo(200);
		assertThat(new String(health.body(), StandardCharsets.UTF_8)).contains("\"UP\"");
	}

	@Test
	void onlyHealthIsExposedFromActuator() {
		assertThat(this.http.get("/actuator/env").statusCode()).isEqualTo(404);
	}

}
