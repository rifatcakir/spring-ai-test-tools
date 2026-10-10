package io.github.rifatcakir.springai.testtools.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.Assert;

/**
 * Each namespace's default mode (PRD fork b), persisted so a restart keeps it.
 *
 * <p>Only a default. The client decides: a mode set in an application's own config always
 * wins, in either direction, and this value applies only to clients that set none. That is
 * what makes "switch the whole QA environment to {@code RECORD_ALWAYS} for an afternoon"
 * one admin call instead of a redeploy, without ever overriding a team that pinned {@code
 * REPLAY_ONLY} on purpose.
 *
 * <p>Kept in {@code {storage-dir}/.cassette-server/namespace-modes.json}, outside every
 * namespace directory, so copying or exporting a namespace carries fixtures only. A name
 * starting with a dot can never be a namespace, so the directory is never listed as one.
 *
 * @author Rifat Cakir
 */
public class NamespaceModes {

	/** The four modes of the library's {@code VcrMode}; the server has no dependency on it. */
	public enum Mode {

		RECORD_OR_REPLAY, REPLAY_ONLY, RECORD_ALWAYS, BYPASS

	}

	private static final Logger logger = LoggerFactory.getLogger(NamespaceModes.class);

	private final Path file;

	private final JsonMapper jsonMapper;

	private final Mode serverDefault;

	private final Map<String, Mode> modes = new TreeMap<>();

	public NamespaceModes(Path storageRoot, JsonMapper jsonMapper, Mode serverDefault) {
		Assert.notNull(storageRoot, "storageRoot must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(serverDefault, "serverDefault must not be null");
		this.file = storageRoot.toAbsolutePath().normalize().resolve(".cassette-server").resolve("namespace-modes.json");
		this.jsonMapper = jsonMapper;
		this.serverDefault = serverDefault;
		load();
	}

	public Mode getServerDefault() {
		return this.serverDefault;
	}

	/**
	 * The mode set for {@code namespace}, if an admin set one.
	 */
	public synchronized Optional<Mode> get(String namespace) {
		return Optional.ofNullable(this.modes.get(namespace));
	}

	/**
	 * The mode a client without its own should use: the namespace's, else the server's.
	 */
	public synchronized Mode effective(String namespace) {
		return this.modes.getOrDefault(namespace, this.serverDefault);
	}

	public synchronized void set(String namespace, Mode mode) {
		Assert.isTrue(FixtureValidator.isValidNamespace(namespace), "invalid namespace");
		Assert.notNull(mode, "mode must not be null");
		this.modes.put(namespace, mode);
		save();
		logger.info("CASSETTE namespace '{}' default mode is now {}", namespace, mode);
	}

	public synchronized boolean clear(String namespace) {
		boolean removed = this.modes.remove(namespace) != null;
		if (removed) {
			save();
			logger.info("CASSETTE namespace '{}' default mode cleared; clients without a mode use {}", namespace,
					this.serverDefault);
		}
		return removed;
	}

	public synchronized Map<String, Mode> all() {
		return Map.copyOf(this.modes);
	}

	private void load() {
		if (!Files.isRegularFile(this.file)) {
			return;
		}
		try {
			Map<String, Mode> stored = this.jsonMapper.readValue(Files.readString(this.file),
					new TypeReference<Map<String, Mode>>() {
					});
			stored.forEach((namespace, mode) -> {
				if (FixtureValidator.isValidNamespace(namespace) && mode != null) {
					this.modes.put(namespace, mode);
				}
			});
			logger.info("CASSETTE loaded default modes for {} namespace(s) from {}", this.modes.size(), this.file);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not read " + this.file, ex);
		}
		catch (RuntimeException ex) {
			// A mangled settings file must not stop the fixture store from starting;
			// every namespace falls back to the server default, loudly.
			logger.warn("CASSETTE could not parse {}; every namespace uses the server default {}", this.file,
					this.serverDefault, ex);
		}
	}

	private void save() {
		try {
			Files.createDirectories(this.file.getParent());
			Path temp = Files.createTempFile(this.file.getParent(), "namespace-modes", ".tmp");
			try {
				Files.writeString(temp, this.jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this.modes));
				try {
					Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (AtomicMoveNotSupportedException ex) {
					Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			finally {
				Files.deleteIfExists(temp);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not write " + this.file, ex);
		}
	}

}
