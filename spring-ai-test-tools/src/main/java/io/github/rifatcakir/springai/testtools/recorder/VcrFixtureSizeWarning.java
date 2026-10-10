package io.github.rifatcakir.springai.testtools.recorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;

/**
 * Warns when a freshly written fixture is large enough to be worth a second look.
 *
 * <p>Shared by the chat, streaming and tool-execution stores rather than duplicated three
 * times — unlike the stores themselves, which stay separate classes on purpose (one extra
 * type parameter for three callers would be the premature abstraction), this is a
 * stateless size check with no type to parameterize.
 *
 * <p><b>Why a warning and not a limit.</b> A large fixture is completely valid: it
 * replays correctly, it is not corrupt, and a test that genuinely needs a big retrieved
 * document in its prompt is a legitimate test. Failing the build would block exactly the
 * RAG-shaped testing this library wants people to do. So this never throws and never
 * refuses a write — it only makes the cost visible at the moment it is incurred.
 *
 * <p><b>Why size alone is the wrong thing to worry about, and what this is really
 * for.</b> A committed JSON fixture is zlib-compressed inside git's packfile, and one
 * large file committed once is not a problem any repository notices. What actually grows
 * a repository is <i>size × churn</i> — the same large fixture re-recorded twenty times
 * leaves twenty blobs in history, and nothing ever removes them. That makes this warning
 * most valuable precisely when bulk re-recording exists (see {@code docs/ROADMAP.md}'s
 * fixture-lifecycle item), which is also when it is easiest to grow history without
 * noticing.
 *
 * <p>Just as importantly, this is an instrument before it is a fix. Whether large-context
 * cassette bloat is a real problem for real users is currently an assumption, not a
 * measurement — the largest committed chat fixture in this project's own example repo is
 * a few kilobytes. If nobody ever trips this threshold, that is a finding worth having
 * before building compression, external blob storage, or a large-fixture mode, each of
 * which trades away the readable-diff property design rule #5 exists to protect. The full
 * large-fixture policy stays deferred in {@code docs/ROADMAP.md} until real demand shows
 * up; this warning is what would produce the evidence of it.
 *
 * <p><b>Applied to every fixture family, embeddings included.</b> An embedding fixture's
 * size is driven by the model's dimensionality rather than by anything the test author
 * wrote, which would make a low threshold unactionable noise for it — but at the default
 * threshold that concern does not bite: a 2048-dimension vector lands around 28 KB and
 * even a 3072-dimension one stays well under, so an embedding fixture large enough to
 * trip this is genuinely unusual (a very large batch of inputs in one call) and worth the
 * same second look as an oversized prompt.
 *
 * @author Rifat Cakir
 */
public final class VcrFixtureSizeWarning {

	/**
	 * 256 KiB. Chosen well above anything this project has actually measured rather than
	 * as a tight budget: the largest committed chat fixture in the sibling example project
	 * is roughly 3.6 KB and the largest embedding fixture roughly 28 KB, so no fixture in
	 * existence today comes close, and a warning that fires on ordinary work is a warning
	 * reviewers learn to scroll past. Tripping this means a fixture is around two orders
	 * of magnitude larger than the current norm — a deliberate large-context or
	 * large-batch test, which is exactly the case worth surfacing.
	 */
	public static final long DEFAULT_THRESHOLD_BYTES = 256L * 1024L;

	private VcrFixtureSizeWarning() {
	}

	/**
	 * Log a warning if the file at {@code path} is at or above {@code thresholdBytes}.
	 *
	 * <p>Never throws. The fixture has already been written and moved into place by the
	 * time this runs, so a failure to stat the file must not turn a successful recording
	 * into a build failure — an unreadable size is simply not reported.
	 * @param logger the calling store's own logger, so the warning is attributed to the
	 * store that wrote the fixture rather than to this helper
	 * @param logPrefix the calling store's log prefix, e.g. {@code "VCR"} or
	 * {@code "VCR STREAM"}, matching its other log lines
	 * @param path the fixture that was just written
	 * @param thresholdBytes the size at or above which to warn; zero or negative disables
	 * the check entirely
	 */
	public static void warnIfLarge(Logger logger, String logPrefix, Path path, long thresholdBytes) {
		if (thresholdBytes <= 0) {
			return;
		}
		long size;
		try {
			size = Files.size(path);
		}
		catch (IOException | RuntimeException ex) {
			logger.debug("{} could not determine the size of {}; skipping the large-fixture check", logPrefix, path,
					ex);
			return;
		}
		if (size < thresholdBytes) {
			return;
		}
		// Deliberately ASCII-only: this line is read in whatever terminal or CI log viewer
		// the developer happens to have, and a non-ASCII dash renders as a replacement
		// character on a Windows console that is not in a UTF-8 code page.
		logger.warn("{} LARGE FIXTURE  {} is {} (threshold {}). Large committed fixtures bloat the repository and "
				+ "are not readably diffable in a pull request - see the Limitations section in the docs. Advisory "
				+ "only: the fixture was written and replays normally. Set {}.fixture-size-warn-threshold=0 to "
				+ "silence.", logPrefix, path, format(size), format(thresholdBytes), "spring.ai.test.vcr");
	}

	/**
	 * Render a byte count the way the threshold property is written, so the warning and
	 * the setting a reader would change are directly comparable.
	 *
	 * <p>{@link Locale#ROOT} explicitly, not the JVM default: on a machine with a
	 * comma-decimal locale the default would render {@code 64KB} as "64,0 KB", which reads
	 * as a thousands separator to everyone else and does not match the {@code 64KB} syntax
	 * the property itself accepts. Caught by actually reading the emitted line on a tr_TR
	 * JVM, not predicted.
	 */
	private static String format(long bytes) {
		if (bytes >= 1024L * 1024L) {
			return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
		}
		if (bytes >= 1024L) {
			return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		}
		return bytes + " B";
	}

}
