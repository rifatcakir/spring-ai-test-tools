package io.github.rifatcakir.springai.testtools.server;

import java.io.IOException;
import java.io.Reader;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's half of the fixture contract (PRD fork d); the library's half is its own
 * {@code FixtureContractTests}. The samples in {@code fixture-contract/} were written by
 * the library's file stores, so these tests prove a library-recorded fixture is accepted
 * unchanged, served back byte for byte, and lands on disk exactly where the library would
 * have put it, which is what makes a server namespace directory and a project's {@code
 * src/test/resources} interchangeable in both directions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FixtureContractTests {

	static final Path CONTRACT = Path.of("..", "fixture-contract").toAbsolutePath().normalize();

	@TempDir
	static Path storageDir;

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		registry.add("cassette.storage-dir", () -> storageDir.toString());
	}

	@LocalServerPort
	int port;

	@Test
	void schemaVersionsMatchTheLibrarysContract() throws IOException {
		Properties versions = new Properties();
		try (Reader reader = Files.newBufferedReader(CONTRACT.resolve("schema-versions.properties"))) {
			versions.load(reader);
		}
		assertThat(versions.stringPropertyNames()).containsExactlyInAnyOrder("chat", "stream", "embedding", "tool");
		for (FixtureKind kind : FixtureKind.values()) {
			assertThat(versions.getProperty(kind.pathSegment())).as(kind.name())
				.isEqualTo(String.valueOf(kind.latestSchemaVersion()));
		}
	}

	@Test
	void everyLibraryWrittenSampleIsAcceptedServedByteForByteAndStoredInTheSamePlace() throws IOException {
		HttpTestClient http = new HttpTestClient(this.port);
		List<Sample> samples = samples();
		assertThat(samples).extracting(Sample::kind)
			.containsExactlyInAnyOrder(FixtureKind.CHAT, FixtureKind.STREAM, FixtureKind.EMBEDDING, FixtureKind.TOOL);

		for (Sample sample : samples) {
			String path = "/api/v1/ns/contract/" + sample.kind().pathSegment() + "/" + sample.hash();
			assertThat(http.put(path, sample.bytes()).statusCode()).as(sample.relativePath()).isEqualTo(201);

			HttpResponse<byte[]> get = http.get(path);
			assertThat(get.statusCode()).isEqualTo(200);
			assertThat(get.body()).as(sample.relativePath()).isEqualTo(sample.bytes());
			assertThat(storageDir.resolve("contract").resolve(sample.relativePath())).as("same relative path")
				.hasBinaryContent(sample.bytes());
		}
	}

	@Test
	void aContractDirectoryCopiedIntoTheStorageIsServedAsANamespace() throws IOException {
		FileSystemUtils.copyRecursively(CONTRACT, storageDir.resolve("copied"));
		HttpTestClient http = new HttpTestClient(this.port);

		for (Sample sample : samples()) {
			HttpResponse<byte[]> get = http
				.get("/api/v1/ns/copied/" + sample.kind().pathSegment() + "/" + sample.hash());
			assertThat(get.statusCode()).as(sample.relativePath()).isEqualTo(200);
			assertThat(get.body()).isEqualTo(sample.bytes());
		}
	}

	private static List<Sample> samples() throws IOException {
		try (Stream<Path> files = Files.walk(CONTRACT)) {
			return files.filter(path -> path.toString().endsWith(".json")).map(FixtureContractTests::sample).toList();
		}
	}

	private static Sample sample(Path file) {
		try {
			byte[] bytes = Files.readAllBytes(file);
			String name = file.getFileName().toString();
			String hash = name.substring(0, name.length() - ".json".length());
			FixtureKind kind = FixtureKind.detect(tools.jackson.databind.json.JsonMapper.builder().build().readTree(bytes));
			assertThat(kind).as("kind of %s", file).isNotNull();
			assertThat(file.getParent().getFileName().toString()).isEqualTo(kind.directory());
			return new Sample(kind, hash, CONTRACT.relativize(file).toString(), bytes);
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	record Sample(FixtureKind kind, String hash, String relativePath, byte[] bytes) {
	}

}
