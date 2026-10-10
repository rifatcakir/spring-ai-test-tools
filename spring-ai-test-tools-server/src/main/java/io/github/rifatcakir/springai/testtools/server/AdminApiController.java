package io.github.rifatcakir.springai.testtools.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin API under {@code /__admin} (PRD 6.3); every request needs the admin token,
 * see {@link AccessTokenInterceptor}.
 *
 * <p>Deleting one fixture is the supported way to force a re-record: the next client
 * that sends that prompt misses and records it again (or, in {@code REPLAY_ONLY}, fails
 * loudly, which is how a team notices the deletion mattered).
 *
 * @author Rifat Cakir
 */
@RestController
public class AdminApiController {

	private static final Logger logger = LoggerFactory.getLogger(AdminApiController.class);

	private final FixtureStorage storage;

	private final NamespaceModes modes;

	private final MissLog missLog;

	private final NamespaceExport export;

	public AdminApiController(FixtureStorage storage, NamespaceModes modes, MissLog missLog, NamespaceExport export) {
		Assert.notNull(storage, "storage must not be null");
		Assert.notNull(modes, "modes must not be null");
		Assert.notNull(missLog, "missLog must not be null");
		Assert.notNull(export, "export must not be null");
		this.storage = storage;
		this.modes = modes;
		this.missLog = missLog;
		this.export = export;
	}

	/**
	 * Namespaces with fixtures on disk, plus those that only have a default mode so far:
	 * an admin may set a mode before any client has recorded anything.
	 */
	@GetMapping("/__admin/namespaces")
	public List<Map<String, Object>> namespaces() {
		TreeSet<String> names = new TreeSet<>(this.storage.namespaces());
		names.addAll(this.modes.all().keySet());
		List<Map<String, Object>> result = new ArrayList<>();
		for (String namespace : names) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("namespace", namespace);
			entry.put("mode", this.modes.effective(namespace).name());
			entry.put("modeSource", this.modes.get(namespace).isPresent() ? "namespace" : "server-default");
			entry.put("fixtures", countsOf(namespace));
			result.add(entry);
		}
		return result;
	}

	/**
	 * Set the namespace's default mode; body {@code {"mode": "REPLAY_ONLY"}}. Applies to
	 * clients that configure no mode of their own, from their next call on.
	 */
	@PutMapping("/__admin/namespaces/{ns}/mode")
	public ResponseEntity<?> setMode(@PathVariable String ns, @RequestBody(required = false) @Nullable Map<String, Object> body) {
		if (!FixtureValidator.isValidNamespace(ns)) {
			return ClientApiController.error(HttpStatus.BAD_REQUEST, "invalid namespace '" + ns + "'");
		}
		Object requested = (body != null) ? body.get("mode") : null;
		NamespaceModes.Mode mode = parseMode(requested);
		if (mode == null) {
			return ClientApiController.error(HttpStatus.BAD_REQUEST, "body must be {\"mode\": \"...\"} with one of "
					+ Arrays.toString(NamespaceModes.Mode.values()) + ", got " + requested);
		}
		this.modes.set(ns, mode);
		return ResponseEntity.ok(ClientApiController.configOf(this.modes, ns));
	}

	/**
	 * Remove the namespace's default, so clients without a mode use the server default.
	 */
	@DeleteMapping("/__admin/namespaces/{ns}/mode")
	public ResponseEntity<?> clearMode(@PathVariable String ns) {
		if (!FixtureValidator.isValidNamespace(ns)) {
			return ClientApiController.error(HttpStatus.BAD_REQUEST, "invalid namespace '" + ns + "'");
		}
		this.modes.clear(ns);
		return ResponseEntity.ok(ClientApiController.configOf(this.modes, ns));
	}

	/**
	 * Recent client-API misses, newest first, each with whether the fixture exists now
	 * (someone recorded it since, or not).
	 */
	@GetMapping("/__admin/namespaces/{ns}/misses")
	public ResponseEntity<?> misses(@PathVariable String ns) {
		if (!FixtureValidator.isValidNamespace(ns)) {
			return ClientApiController.error(HttpStatus.BAD_REQUEST, "invalid namespace '" + ns + "'");
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (MissLog.Miss miss : this.missLog.recent(ns)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("at", miss.at().toString());
			entry.put("kind", miss.kind().pathSegment());
			entry.put("hash", miss.hash());
			entry.put("recordedSince", this.storage.read(ns, miss.kind(), miss.hash()).isPresent());
			result.add(entry);
		}
		return ResponseEntity.ok(result);
	}

	/**
	 * The namespace as a zip in the library's file layout: unzip into {@code
	 * src/test/resources}, commit, review the diff.
	 */
	@GetMapping("/__admin/namespaces/{ns}/export")
	public ResponseEntity<?> export(@PathVariable String ns) {
		if (!FixtureValidator.isValidNamespace(ns)) {
			return ClientApiController.error(HttpStatus.BAD_REQUEST, "invalid namespace '" + ns + "'");
		}
		if (!this.storage.namespaces().contains(ns)) {
			return ClientApiController.error(HttpStatus.NOT_FOUND, "no namespace " + ns);
		}
		// Built in memory rather than streamed: a fixture namespace is a test asset of
		// kilobytes to a few megabytes, and a complete body means a failure half-way is a
		// 500 instead of a truncated zip the caller might commit.
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		try {
			int count = this.export.writeZip(ns, body);
			logger.info("CASSETTE EXPORTED namespace '{}' ({} fixture(s))", ns, count);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not export namespace " + ns, ex);
		}
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType("application/zip"))
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ns + "-fixtures.zip\"")
			.body(body.toByteArray());
	}

	private static NamespaceModes.@Nullable Mode parseMode(@Nullable Object requested) {
		if (!(requested instanceof String text)) {
			return null;
		}
		try {
			return NamespaceModes.Mode.valueOf(text.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	@GetMapping("/__admin/namespaces/{ns}/fixtures")
	public ResponseEntity<?> fixtures(@PathVariable String ns, @RequestParam(required = false) @Nullable String kind) {
		if (!FixtureValidator.isValidNamespace(ns)) {
			return ClientApiController.error(HttpStatus.BAD_REQUEST, "invalid namespace '" + ns + "'");
		}
		FixtureKind only = null;
		if (kind != null) {
			only = FixtureKind.fromPathSegment(kind);
			if (only == null) {
				return ClientApiController.error(HttpStatus.BAD_REQUEST,
						"unknown fixture kind '" + kind + "': chat, stream, embedding or tool");
			}
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (FixtureSummary summary : this.storage.list(ns, only)) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("hash", summary.hash());
			entry.put("kind", summary.kind().pathSegment());
			entry.put("recordedAt", summary.recordedAt());
			entry.put("model", summary.model());
			entry.put("summary", summary.summary());
			result.add(entry);
		}
		return ResponseEntity.ok(result);
	}

	@GetMapping("/__admin/namespaces/{ns}/fixtures/{kind}/{hash}")
	public ResponseEntity<?> fixture(@PathVariable String ns, @PathVariable String kind, @PathVariable String hash) {
		FixtureKind fixtureKind = FixtureKind.fromPathSegment(kind);
		ResponseEntity<?> invalid = ClientApiController.checkPath(ns, fixtureKind, kind, hash);
		if (invalid != null) {
			return invalid;
		}
		Optional<byte[]> document = this.storage.read(ns, fixtureKind, hash);
		if (document.isEmpty()) {
			return ClientApiController.error(HttpStatus.NOT_FOUND,
					"no " + kind + " fixture " + hash + " in namespace " + ns);
		}
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(document.get());
	}

	@DeleteMapping("/__admin/namespaces/{ns}/fixtures/{kind}/{hash}")
	public ResponseEntity<?> delete(@PathVariable String ns, @PathVariable String kind, @PathVariable String hash) {
		FixtureKind fixtureKind = FixtureKind.fromPathSegment(kind);
		ResponseEntity<?> invalid = ClientApiController.checkPath(ns, fixtureKind, kind, hash);
		if (invalid != null) {
			return invalid;
		}
		if (!this.storage.delete(ns, fixtureKind, hash)) {
			return ClientApiController.error(HttpStatus.NOT_FOUND,
					"no " + kind + " fixture " + hash + " in namespace " + ns);
		}
		return ResponseEntity.noContent().build();
	}

	private Map<String, Integer> countsOf(String namespace) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		this.storage.counts(namespace).forEach((kind, count) -> counts.put(kind.pathSegment(), count));
		return counts;
	}

}
