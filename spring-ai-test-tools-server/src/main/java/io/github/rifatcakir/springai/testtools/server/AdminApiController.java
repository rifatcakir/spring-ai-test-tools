package io.github.rifatcakir.springai.testtools.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

	private final FixtureStorage storage;

	public AdminApiController(FixtureStorage storage) {
		Assert.notNull(storage, "storage must not be null");
		this.storage = storage;
	}

	@GetMapping("/__admin/namespaces")
	public List<Map<String, Object>> namespaces() {
		List<Map<String, Object>> result = new ArrayList<>();
		for (String namespace : this.storage.namespaces()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("namespace", namespace);
			entry.put("fixtures", countsOf(namespace));
			result.add(entry);
		}
		return result;
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
