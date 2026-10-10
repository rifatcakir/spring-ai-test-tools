package io.github.rifatcakir.springai.testtools.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API the library's remote store talks to (PRD 6.2).
 *
 * <p>{@code PUT} answers {@code 409} with the existing document when one is already
 * stored: two instances that miss on the same prompt at the same time both call the
 * model, and the second one to write must keep the first recording rather than replace
 * it (PRD 5.3), so it needs to know that it lost. Only {@code ?overwrite=true}, which the
 * client sends under {@code RECORD_ALWAYS}, replaces an existing fixture.
 *
 * @author Rifat Cakir
 */
@RestController
public class ClientApiController {

	private static final Logger logger = LoggerFactory.getLogger(ClientApiController.class);

	private final FixtureStorage storage;

	private final FixtureValidator validator;

	private final NamespaceModes modes;

	private final MissLog missLog;

	public ClientApiController(FixtureStorage storage, FixtureValidator validator, NamespaceModes modes,
			MissLog missLog) {
		Assert.notNull(storage, "storage must not be null");
		Assert.notNull(validator, "validator must not be null");
		Assert.notNull(modes, "modes must not be null");
		Assert.notNull(missLog, "missLog must not be null");
		this.storage = storage;
		this.validator = validator;
		this.modes = modes;
		this.missLog = missLog;
	}

	/**
	 * The mode for clients that set none (PRD fork b). A client with its own mode never
	 * asks; one without asks on every call, so an admin's change applies to the next call
	 * rather than the next restart.
	 */
	@GetMapping("/api/v1/ns/{ns}/config")
	public ResponseEntity<?> config(@PathVariable String ns) {
		if (!FixtureValidator.isValidNamespace(ns)) {
			return error(HttpStatus.BAD_REQUEST, "invalid namespace '" + ns + "'");
		}
		return ResponseEntity.ok(configOf(this.modes, ns));
	}

	static Map<String, Object> configOf(NamespaceModes modes, String ns) {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("namespace", ns);
		config.put("mode", modes.effective(ns).name());
		config.put("source", modes.get(ns).isPresent() ? "namespace" : "server-default");
		return config;
	}

	@GetMapping("/api/v1/ns/{ns}/{kind}/{hash}")
	public ResponseEntity<?> get(@PathVariable String ns, @PathVariable String kind, @PathVariable String hash) {
		FixtureKind fixtureKind = FixtureKind.fromPathSegment(kind);
		ResponseEntity<?> invalid = checkPath(ns, fixtureKind, kind, hash);
		if (invalid != null) {
			return invalid;
		}
		Optional<byte[]> document = this.storage.read(ns, fixtureKind, hash);
		if (document.isEmpty()) {
			logger.info("CASSETTE MISS [{}/{}/{}]", ns, kind, FixtureStorage.shortHash(hash));
			this.missLog.record(ns, fixtureKind, hash);
			return error(HttpStatus.NOT_FOUND, "no " + kind + " fixture " + hash + " in namespace " + ns);
		}
		logger.info("CASSETTE HIT  [{}/{}/{}]", ns, kind, FixtureStorage.shortHash(hash));
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(document.get());
	}

	@PutMapping("/api/v1/ns/{ns}/{kind}/{hash}")
	public ResponseEntity<?> put(@PathVariable String ns, @PathVariable String kind, @PathVariable String hash,
			@RequestParam(defaultValue = "false") boolean overwrite, @RequestBody(required = false) byte[] body) {
		FixtureKind fixtureKind = FixtureKind.fromPathSegment(kind);
		ResponseEntity<?> invalid = checkPath(ns, fixtureKind, kind, hash);
		if (invalid != null) {
			return invalid;
		}
		try {
			this.validator.validate(fixtureKind, hash, (body != null) ? body : new byte[0]);
		}
		catch (FixtureValidator.InvalidFixtureException ex) {
			logger.warn("CASSETTE refused [{}/{}/{}]: {}", ns, kind, FixtureStorage.shortHash(hash), ex.getMessage());
			return error(HttpStatus.BAD_REQUEST, ex.getMessage());
		}

		if (overwrite) {
			boolean replaced = this.storage.replace(ns, fixtureKind, hash, body);
			logger.info("CASSETTE {} [{}/{}/{}] (overwrite)", replaced ? "REPLACED" : "STORED  ", ns, kind,
					FixtureStorage.shortHash(hash));
			return ResponseEntity.status(replaced ? HttpStatus.OK : HttpStatus.CREATED).build();
		}

		FixtureStorage.WriteResult result = this.storage.create(ns, fixtureKind, hash, body);
		if (result.isCreated()) {
			logger.info("CASSETTE STORED   [{}/{}/{}]", ns, kind, FixtureStorage.shortHash(hash));
			return ResponseEntity.status(HttpStatus.CREATED).build();
		}
		logger.info("CASSETTE CONFLICT [{}/{}/{}] keeping the existing fixture", ns, kind,
				FixtureStorage.shortHash(hash));
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.contentType(MediaType.APPLICATION_JSON)
			.body(result.existingDocument());
	}

	static ResponseEntity<?> checkPath(String ns, FixtureKind kind, String rawKind, String hash) {
		if (!FixtureValidator.isValidNamespace(ns)) {
			return error(HttpStatus.BAD_REQUEST,
					"invalid namespace '" + ns + "': letters, digits, '.', '_' and '-', starting with a letter or digit");
		}
		if (kind == null) {
			return error(HttpStatus.NOT_FOUND, "unknown fixture kind '" + rawKind + "': chat, stream, embedding or tool");
		}
		if (!FixtureValidator.isValidHash(hash)) {
			return error(HttpStatus.BAD_REQUEST, "invalid hash '" + hash + "': expected 64 lowercase hex characters");
		}
		return null;
	}

	static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
	}

}
