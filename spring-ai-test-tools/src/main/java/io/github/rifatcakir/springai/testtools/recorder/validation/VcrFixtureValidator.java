package io.github.rifatcakir.springai.testtools.recorder.validation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrack;
import io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrack;
import io.github.rifatcakir.springai.testtools.recorder.stream.VcrStreamTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrack;
import io.github.rifatcakir.springai.testtools.recorder.tool.VcrToolExecutionTrackStore;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore;

import org.springframework.util.Assert;

/**
 * Checks a directory of committed fixtures for integrity, with no model, no network, and
 * no live request to compare against — only what is already on disk.
 *
 * <p>Two things are checked per fixture, both real, both found by reading this project's
 * own store classes rather than assumed:
 * <ol>
 * <li><strong>It parses.</strong> Every {@code *TrackStore#read} already degrades a
 * malformed fixture to "absent" rather than throwing (design rule #7) — the right
 * behaviour for a single replay, where a corrupt file should fall back to re-recording,
 * not wedge the build. But that same tolerance means nothing today <em>notices</em> a
 * corrupt fixture until a test happens to ask for that exact hash. This surfaces it
 * proactively, for every fixture in a directory, whether or not the current test run
 * exercises it.</li>
 * <li><strong>Its filename matches its own recorded hash.</strong> A genuine gap, not a
 * hypothetical one: {@code VcrTrackStore#read(String)} resolves a file purely by the
 * hash <em>parameter</em> it is given and returns whatever {@code VcrTrack} is inside,
 * never checking that the deserialized {@code track.hash()} actually equals the filename
 * it was read from. A renamed file, a fixture copy-pasted from a different hash's file
 * during a merge, or a hand-edited {@code "hash"} field would replay without complaint —
 * this is the check that would catch it.</li>
 * </ol>
 *
 * <p><strong>What this does not, and cannot, check</strong>: whether a fixture is still
 * the <em>correct</em> answer for the live request that would produce its hash today.
 * That would require re-issuing the original request against a real model, which is
 * exactly the cost this whole library exists to avoid paying in CI — see {@code
 * VcrMode#REPLAY_ONLY} for the mechanism that already does surface a genuinely stale or
 * missing fixture, the moment a real test asks for it.
 *
 * <p>Never throws for a fixture-level problem — a corrupt file is reported like any other
 * problem, in the returned list, not thrown. A caller decides what "found any problems"
 * means for its own build:
 *
 * <pre>{@code
 * @Test
 * void everyCommittedChatFixtureIsIntact() {
 *     List<VcrFixtureProblem> problems = VcrFixtureValidator
 *         .validateChatFixtures(Path.of("src/test/resources/llm-cache"));
 *     assertThat(problems).isEmpty();
 * }
 * }</pre>
 *
 * <p>One method per fixture family, not one method that walks a whole tree and guesses
 * which directory holds which shape: this project has no single canonical directory
 * layout to discover (a consumer's own {@code spring.ai.test.vcr.*.cache-directory}
 * properties, or a per-test-class directory the way this project's own example project
 * uses, are both real, valid layouts) — the caller already knows which directory holds
 * which kind of fixture, the same way it already configures that mapping today.
 *
 * @author Rifat Cakir
 */
public final class VcrFixtureValidator {

	private static final Pattern HASH_FILENAME = Pattern.compile("[0-9a-f]{64}\\.json");

	private VcrFixtureValidator() {
	}

	/**
	 * Validate a directory of chat/streaming-call fixtures ({@link VcrTrack}).
	 * @param cacheDirectory the directory to check; a missing directory is not itself a
	 * problem (nothing has been recorded there yet) and yields an empty list
	 * @return every problem found, empty if the directory is clean
	 */
	public static List<VcrFixtureProblem> validateChatFixtures(Path cacheDirectory) {
		VcrTrackStore store = new VcrTrackStore(cacheDirectory);
		return validate(cacheDirectory, store::read, VcrTrack::hash);
	}

	/**
	 * Validate a directory of streamed-response fixtures ({@link VcrStreamTrack}).
	 * @param cacheDirectory the directory to check; a missing directory yields an empty
	 * list
	 * @return every problem found, empty if the directory is clean
	 */
	public static List<VcrFixtureProblem> validateStreamFixtures(Path cacheDirectory) {
		VcrStreamTrackStore store = new VcrStreamTrackStore(cacheDirectory);
		return validate(cacheDirectory, store::read, VcrStreamTrack::hash);
	}

	/**
	 * Validate a directory of embedding fixtures ({@link VcrEmbeddingTrack}).
	 * @param cacheDirectory the directory to check; a missing directory yields an empty
	 * list
	 * @return every problem found, empty if the directory is clean
	 */
	public static List<VcrFixtureProblem> validateEmbeddingFixtures(Path cacheDirectory) {
		VcrEmbeddingTrackStore store = new VcrEmbeddingTrackStore(cacheDirectory);
		return validate(cacheDirectory, store::read, VcrEmbeddingTrack::hash);
	}

	/**
	 * Validate a directory of tool-execution fixtures ({@link VcrToolExecutionTrack}).
	 * @param cacheDirectory the directory to check; a missing directory yields an empty
	 * list
	 * @return every problem found, empty if the directory is clean
	 */
	public static List<VcrFixtureProblem> validateToolFixtures(Path cacheDirectory) {
		VcrToolExecutionTrackStore store = new VcrToolExecutionTrackStore(cacheDirectory);
		return validate(cacheDirectory, store::read, VcrToolExecutionTrack::hash);
	}

	/**
	 * Shared core behind all four {@code validate*Fixtures} methods above — one small
	 * generic helper used only by this class's own four public methods, not a new
	 * abstraction imposed on the store classes themselves, which stay untouched and
	 * unaware this exists.
	 */
	private static <T> List<VcrFixtureProblem> validate(Path cacheDirectory, Function<String, Optional<T>> reader,
			Function<T, String> hashOf) {
		Assert.notNull(cacheDirectory, "cacheDirectory must not be null");
		List<VcrFixtureProblem> problems = new ArrayList<>();
		if (!Files.isDirectory(cacheDirectory)) {
			return problems;
		}
		try (Stream<Path> entries = Files.list(cacheDirectory)) {
			for (Path file : entries.filter(Files::isRegularFile).sorted().toList()) {
				String filename = file.getFileName().toString();
				if (!HASH_FILENAME.matcher(filename).matches()) {
					problems.add(new VcrFixtureProblem(file, "filename is not a 64-character lowercase hex hash "
							+ "followed by .json -- not a fixture this store would ever have written itself"));
					continue;
				}

				String hashFromFilename = filename.substring(0, filename.length() - ".json".length());
				Optional<T> track = reader.apply(hashFromFilename);
				if (track.isEmpty()) {
					problems.add(new VcrFixtureProblem(file, "does not parse as a valid fixture -- malformed JSON, "
							+ "or a shape this library's current schema cannot read"));
					continue;
				}

				String storedHash = hashOf.apply(track.get());
				if (!hashFromFilename.equals(storedHash)) {
					problems.add(new VcrFixtureProblem(file,
							"filename hash does not match the fixture's own recorded hash (" + storedHash
									+ ") -- the file was renamed, copied from a different fixture, or hand-edited"));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("VCR VALIDATE failed to list " + cacheDirectory, ex);
		}
		return problems;
	}

}
