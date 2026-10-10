package io.github.rifatcakir.springai.testtools.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureStorageTests {

	private static final String HASH = TestDocuments.hash("storage");

	@TempDir
	Path root;

	private FixtureStorage storage() {
		JsonMapper mapper = JsonMapper.builder().build();
		return new FixtureStorage(this.root, new FixtureValidator(mapper), mapper);
	}

	@Test
	void usesTheLibrarysFileLayoutWithChatAndStreamSharingLlmCache() {
		FixtureStorage storage = storage();
		assertThat(storage.pathFor("svc", FixtureKind.CHAT, HASH))
			.isEqualTo(this.root.resolve("svc/llm-cache/" + HASH + ".json"));
		assertThat(storage.pathFor("svc", FixtureKind.STREAM, HASH))
			.isEqualTo(this.root.resolve("svc/llm-cache/" + HASH + ".json"));
		assertThat(storage.pathFor("svc", FixtureKind.EMBEDDING, HASH))
			.isEqualTo(this.root.resolve("svc/llm-cache-embedding/" + HASH + ".json"));
		assertThat(storage.pathFor("svc", FixtureKind.TOOL, HASH))
			.isEqualTo(this.root.resolve("svc/llm-cache-tool/" + HASH + ".json"));
	}

	@Test
	void storesTheDocumentByteForByte() throws Exception {
		FixtureStorage storage = storage();
		byte[] document = TestDocuments.embedding(HASH);

		assertThat(storage.create("svc", FixtureKind.EMBEDDING, HASH, document).isCreated()).isTrue();

		assertThat(Files.readAllBytes(storage.pathFor("svc", FixtureKind.EMBEDDING, HASH))).isEqualTo(document);
		assertThat(storage.read("svc", FixtureKind.EMBEDDING, HASH)).hasValue(document);
	}

	@Test
	void aSecondCreateKeepsTheFirstDocumentAndReturnsIt() {
		FixtureStorage storage = storage();
		byte[] first = TestDocuments.chat(HASH, "first");
		storage.create("svc", FixtureKind.CHAT, HASH, first);

		FixtureStorage.WriteResult second = storage.create("svc", FixtureKind.CHAT, HASH,
				TestDocuments.chat(HASH, "second"));

		assertThat(second.isCreated()).isFalse();
		assertThat(second.existingDocument()).isEqualTo(first);
		assertThat(storage.read("svc", FixtureKind.CHAT, HASH)).hasValue(first);
	}

	@Test
	void replaceOverwritesAndReportsWhetherSomethingWasThere() {
		FixtureStorage storage = storage();
		assertThat(storage.replace("svc", FixtureKind.CHAT, HASH, TestDocuments.chat(HASH, "first"))).isFalse();
		assertThat(storage.replace("svc", FixtureKind.CHAT, HASH, TestDocuments.chat(HASH, "second"))).isTrue();
		assertThat(storage.read("svc", FixtureKind.CHAT, HASH)).hasValue(TestDocuments.chat(HASH, "second"));
	}

	@Test
	void aCorruptFileReadsAsMissingAndIsReplacedByTheNextCreate() throws Exception {
		FixtureStorage storage = storage();
		Path path = storage.pathFor("svc", FixtureKind.CHAT, HASH);
		Files.createDirectories(path.getParent());
		Files.writeString(path, "{ \"schemaVersion\" : \"6\", \"hash\" : \"" + HASH + "\", \"resp", StandardCharsets.UTF_8);

		assertThat(storage.read("svc", FixtureKind.CHAT, HASH)).isEmpty();
		assertThat(storage.create("svc", FixtureKind.CHAT, HASH, TestDocuments.chat(HASH)).isCreated()).isTrue();
		assertThat(storage.read("svc", FixtureKind.CHAT, HASH)).hasValue(TestDocuments.chat(HASH));
	}

	@Test
	void aStreamFixtureIsNeverServedOrDeletedAsChat() {
		FixtureStorage storage = storage();
		storage.create("svc", FixtureKind.STREAM, HASH, TestDocuments.stream(HASH));

		assertThat(storage.read("svc", FixtureKind.CHAT, HASH)).isEmpty();
		assertThat(storage.delete("svc", FixtureKind.CHAT, HASH)).isFalse();
		assertThat(storage.read("svc", FixtureKind.STREAM, HASH)).isPresent();
		assertThat(storage.delete("svc", FixtureKind.STREAM, HASH)).isTrue();
		assertThat(storage.read("svc", FixtureKind.STREAM, HASH)).isEmpty();
	}

	@Test
	void listsAndCountsByKind() {
		FixtureStorage storage = storage();
		for (FixtureKind kind : FixtureKind.values()) {
			String hash = TestDocuments.hash(kind.name());
			storage.create("svc", kind, hash, TestDocuments.of(kind, hash));
		}
		storage.create("other", FixtureKind.CHAT, HASH, TestDocuments.chat(HASH));

		assertThat(storage.namespaces()).containsExactly("other", "svc");
		assertThat(storage.counts("svc")).containsValues(1, 1, 1, 1);
		List<FixtureSummary> chats = storage.list("svc", FixtureKind.CHAT);
		assertThat(chats).singleElement().satisfies(summary -> {
			assertThat(summary.model()).isEqualTo("llama3.2:1b");
			assertThat(summary.summary()).isEqualTo("Capital of France?");
			assertThat(summary.recordedAt()).isEqualTo("2026-10-10T00:00:00Z");
		});
		assertThat(storage.list("svc", FixtureKind.TOOL)).singleElement()
			.extracting(FixtureSummary::summary)
			.isEqualTo("currentWeather({\"city\":\"Paris\"})");
	}

	@Test
	void parallelCreatesOfTheSameHashStoreExactlyOneCompleteDocument() throws Exception {
		FixtureStorage storage = storage();
		int writers = 16;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<FixtureStorage.WriteResult>> results = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(writers)) {
			for (int i = 0; i < writers; i++) {
				byte[] document = TestDocuments.chat(HASH, "writer-" + i + "-" + "x".repeat(20_000));
				results.add(executor.submit(() -> {
					start.await();
					return storage.create("svc", FixtureKind.CHAT, HASH, document);
				}));
			}
			start.countDown();
		}

		List<FixtureStorage.WriteResult> outcomes = new ArrayList<>();
		for (Future<FixtureStorage.WriteResult> result : results) {
			outcomes.add(result.get());
		}
		assertThat(outcomes).filteredOn(FixtureStorage.WriteResult::isCreated).hasSize(1);
		byte[] stored = storage.read("svc", FixtureKind.CHAT, HASH).orElseThrow();
		assertThat(outcomes).filteredOn(outcome -> !outcome.isCreated())
			.hasSize(writers - 1)
			.allSatisfy(outcome -> assertThat(outcome.existingDocument()).isEqualTo(stored));
		try (Stream<Path> files = Files.list(storage.pathFor("svc", FixtureKind.CHAT, HASH).getParent())) {
			assertThat(files).as("no temp files left behind").hasSize(1);
		}
	}

}
