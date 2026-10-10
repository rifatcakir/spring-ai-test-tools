package io.github.rifatcakir.springai.testtools.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.Assert;

/**
 * Fixtures on disk, in exactly the layout the library's file store uses.
 *
 * <pre>
 * {storage-dir}/{namespace}/llm-cache/{hash}.json             chat and stream
 * {storage-dir}/{namespace}/llm-cache-embedding/{hash}.json
 * {storage-dir}/{namespace}/llm-cache-tool/{hash}.json
 * </pre>
 *
 * <p>Documents are stored byte for byte as the client sent them, never re-serialized. The
 * library sends exactly what its own file store would have written (pretty-printed by the
 * same mapper), so a stored file is identical to a locally recorded one. Re-serializing
 * through a JSON tree would be worse than pointless: embedding vectors are {@code float}s,
 * and a round trip through a {@code double}-backed tree changes their textual digits, so
 * a fixture would differ from the same recording made locally.
 *
 * <p>Writes go to a temporary file in the target directory and are then moved into
 * place, the same as the library's stores: a server killed mid-write must not leave a
 * half-written fixture behind for the next client to replay. On top of that, writes to
 * one {@code (namespace, kind, hash)} are serialized in-process, so two clients
 * recording the same prompt at once get one {@code 201} and one {@code 409}, never two
 * {@code 201}s. One server process owns the directory (PRD 6.4); nothing here tries to
 * lock across hosts.
 *
 * @author Rifat Cakir
 */
public class FixtureStorage {

	private static final Logger logger = LoggerFactory.getLogger(FixtureStorage.class);

	private static final String FILE_EXTENSION = ".json";

	private static final int LOCK_STRIPES = 64;

	private static final int SUMMARY_LENGTH = 120;

	private final Path root;

	private final FixtureValidator validator;

	private final JsonMapper jsonMapper;

	/**
	 * Striped rather than one lock per fixture, so the lock table stays bounded however
	 * many fixtures a namespace accumulates. Two different hashes sharing a stripe only
	 * wait for each other's (millisecond) file write.
	 */
	private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

	public FixtureStorage(Path root, FixtureValidator validator, JsonMapper jsonMapper) {
		Assert.notNull(root, "root must not be null");
		Assert.notNull(validator, "validator must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		this.root = root.toAbsolutePath().normalize();
		this.validator = validator;
		this.jsonMapper = jsonMapper;
		for (int i = 0; i < LOCK_STRIPES; i++) {
			this.locks[i] = new ReentrantLock();
		}
	}

	public Path getRoot() {
		return this.root;
	}

	public Path namespaceDirectory(String namespace) {
		Assert.isTrue(FixtureValidator.isValidNamespace(namespace), "invalid namespace");
		return this.root.resolve(namespace);
	}

	public Path pathFor(String namespace, FixtureKind kind, String hash) {
		Assert.notNull(kind, "kind must not be null");
		Assert.isTrue(FixtureValidator.isValidHash(hash), "invalid hash");
		return namespaceDirectory(namespace).resolve(kind.directory()).resolve(hash + FILE_EXTENSION);
	}

	/**
	 * The stored document, or empty if there is none or it is unreadable as {@code kind}.
	 * A corrupt file degrades to "not found" (design rule #7), so the client sees a miss
	 * and re-records it rather than replaying garbage.
	 */
	public Optional<byte[]> read(String namespace, FixtureKind kind, String hash) {
		Path path = pathFor(namespace, kind, hash);
		byte[] stored = readBytes(path);
		if (stored == null) {
			return Optional.empty();
		}
		if (!this.validator.isReadable(kind, hash, stored)) {
			logger.warn("CASSETTE fixture {} is not a readable {} fixture; treating it as missing", path,
					kind.pathSegment());
			return Optional.empty();
		}
		return Optional.of(stored);
	}

	/**
	 * Store {@code document} unless a readable one already exists for the same key.
	 * @return {@link WriteResult#created()} or, when another client got there first,
	 * {@link WriteResult#existing()} carrying the document that was kept
	 */
	public WriteResult create(String namespace, FixtureKind kind, String hash, byte[] document) {
		Path path = pathFor(namespace, kind, hash);
		return locked(path, () -> {
			byte[] stored = readBytes(path);
			if (stored != null) {
				if (this.validator.isReadable(kind, hash, stored)) {
					return WriteResult.existing(stored);
				}
				logger.warn("CASSETTE replacing unreadable fixture {}", path);
			}
			writeAtomically(path, document);
			return WriteResult.created();
		});
	}

	/**
	 * Store {@code document}, replacing whatever is there. Only reachable through an
	 * explicit {@code ?overwrite=true}, which the client sends under {@code RECORD_ALWAYS}
	 * alone (PRD 5.3: last write wins only in that mode).
	 * @return whether a document was replaced rather than newly created
	 */
	public boolean replace(String namespace, FixtureKind kind, String hash, byte[] document) {
		Path path = pathFor(namespace, kind, hash);
		return locked(path, () -> {
			boolean existed = Files.isRegularFile(path);
			writeAtomically(path, document);
			return existed;
		});
	}

	/**
	 * Delete the fixture of {@code kind} for {@code hash}. Chat and stream fixtures share
	 * a directory, so a file is only deleted when it is not recognizably the other kind:
	 * {@code DELETE …/chat/{hash}} must never remove a stream fixture.
	 * @return whether a file was deleted
	 */
	public boolean delete(String namespace, FixtureKind kind, String hash) {
		Path path = pathFor(namespace, kind, hash);
		return locked(path, () -> {
			byte[] stored = readBytes(path);
			if (stored == null) {
				return false;
			}
			FixtureKind detected = detect(stored);
			if (detected != null && detected != kind) {
				return false;
			}
			try {
				boolean deleted = Files.deleteIfExists(path);
				if (deleted) {
					logger.info("CASSETTE DELETED [{}/{}/{}] {}", namespace, kind.pathSegment(), shortHash(hash), path);
				}
				return deleted;
			}
			catch (IOException ex) {
				throw new UncheckedIOException("could not delete " + path, ex);
			}
		});
	}

	/**
	 * Every namespace directory under the storage root, sorted by name.
	 */
	public List<String> namespaces() {
		if (!Files.isDirectory(this.root)) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(this.root, Files::isDirectory)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (FixtureValidator.isValidNamespace(name)) {
					names.add(name);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not list " + this.root, ex);
		}
		names.sort(Comparator.naturalOrder());
		return names;
	}

	/**
	 * Fixture count per kind in a namespace. Every kind is present, zero when empty.
	 */
	public Map<FixtureKind, Integer> counts(String namespace) {
		Map<FixtureKind, Integer> counts = new EnumMap<>(FixtureKind.class);
		for (FixtureKind kind : FixtureKind.values()) {
			counts.put(kind, 0);
		}
		for (FixtureSummary summary : list(namespace, null)) {
			counts.merge(summary.kind(), 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * Summaries of the readable fixtures in a namespace, optionally of one kind, sorted by
	 * kind and hash. Unreadable files are skipped here; {@link #read} already treats them
	 * as missing.
	 */
	public List<FixtureSummary> list(String namespace, @Nullable FixtureKind only) {
		Path namespaceDirectory = namespaceDirectory(namespace);
		List<FixtureSummary> summaries = new ArrayList<>();
		for (String directory : List.of("llm-cache", "llm-cache-embedding", "llm-cache-tool")) {
			Path dir = namespaceDirectory.resolve(directory);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + FILE_EXTENSION)) {
				for (Path file : files) {
					FixtureSummary summary = summarize(directory, file);
					if (summary != null && (only == null || summary.kind() == only)) {
						summaries.add(summary);
					}
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException("could not list " + dir, ex);
			}
		}
		summaries.sort(Comparator.comparing(FixtureSummary::kind).thenComparing(FixtureSummary::hash));
		return summaries;
	}

	private @Nullable FixtureSummary summarize(String directory, Path file) {
		String name = file.getFileName().toString();
		String hash = name.substring(0, name.length() - FILE_EXTENSION.length());
		if (!FixtureValidator.isValidHash(hash)) {
			return null;
		}
		byte[] stored = readBytes(file);
		if (stored == null) {
			return null;
		}
		JsonNode document = parseOrNull(stored);
		FixtureKind kind = (document != null) ? FixtureKind.detect(document) : null;
		if (kind == null || !kind.directory().equals(directory) || !this.validator.isReadable(kind, hash, stored)) {
			return null;
		}
		return FixtureSummary.of(kind, hash, document, SUMMARY_LENGTH);
	}

	private @Nullable FixtureKind detect(byte[] stored) {
		JsonNode document = parseOrNull(stored);
		return (document != null) ? FixtureKind.detect(document) : null;
	}

	private @Nullable JsonNode parseOrNull(byte[] stored) {
		try {
			return this.jsonMapper.readTree(stored);
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	private static byte @Nullable [] readBytes(Path path) {
		try {
			return Files.readAllBytes(path);
		}
		catch (NoSuchFileException ex) {
			return null;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not read " + path, ex);
		}
	}

	private void writeAtomically(Path path, byte[] document) {
		Path directory = path.getParent();
		try {
			Files.createDirectories(directory);
			Path temp = Files.createTempFile(directory, path.getFileName().toString(), ".tmp");
			try {
				Files.write(temp, document);
				try {
					Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (AtomicMoveNotSupportedException ex) {
					// Some Windows and network filesystems refuse atomic moves. A
					// non-atomic replace is still better than writing in place.
					logger.debug("CASSETTE atomic move unsupported at {}; falling back to replace", path);
					Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			finally {
				Files.deleteIfExists(temp);
			}
			logger.debug("CASSETTE wrote {} ({} bytes)", path, document.length);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not write " + path, ex);
		}
	}

	private <T> T locked(Path path, Supplier<T> action) {
		ReentrantLock lock = this.locks[Math.floorMod(path.hashCode(), LOCK_STRIPES)];
		lock.lock();
		try {
			return action.get();
		}
		finally {
			lock.unlock();
		}
	}

	static String shortHash(String hash) {
		return (hash.length() < 12) ? hash : hash.substring(0, 12);
	}

	/**
	 * Outcome of {@link #create}: either stored, or kept the {@code existing} document.
	 */
	public record WriteResult(boolean isCreated, byte @Nullable [] existingDocument) {

		static WriteResult created() {
			return new WriteResult(true, null);
		}

		static WriteResult existing(byte[] document) {
			return new WriteResult(false, document);
		}

	}

}
