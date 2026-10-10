package io.github.rifatcakir.springai.testtools.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.util.Assert;

/**
 * Writes a namespace as a zip in the library's file layout (PRD 6.5).
 *
 * <p>This is how fixtures recorded in a shared environment still get reviewed as diffs
 * (design rule #5): unzip into a project's {@code src/test/resources}, commit, open a pull
 * request. Entries are {@code llm-cache/{hash}.json}, {@code
 * llm-cache-embedding/{hash}.json} and {@code llm-cache-tool/{hash}.json}, holding the
 * stored bytes unchanged, so they replay with {@code store: file} as they are.
 *
 * <p>Only readable fixtures are exported, in a fixed order with a fixed timestamp, so
 * exporting an unchanged namespace twice gives byte-identical zips and a scheduled export
 * job does not produce noise.
 *
 * @author Rifat Cakir
 */
public class NamespaceExport {

	private static final FileTime FIXED_TIME = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));

	private final FixtureStorage storage;

	public NamespaceExport(FixtureStorage storage) {
		Assert.notNull(storage, "storage must not be null");
		this.storage = storage;
	}

	/**
	 * @return the number of fixtures written
	 */
	public int writeZip(String namespace, OutputStream out) throws IOException {
		int count = 0;
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (FixtureSummary summary : this.storage.list(namespace, null)) {
				byte[] document = this.storage.read(namespace, summary.kind(), summary.hash()).orElse(null);
				if (document == null) {
					continue;
				}
				ZipEntry entry = new ZipEntry(summary.kind().directory() + "/" + summary.hash() + ".json");
				entry.setLastModifiedTime(FIXED_TIME);
				zip.putNextEntry(entry);
				zip.write(document);
				zip.closeEntry();
				count++;
			}
		}
		return count;
	}

}
