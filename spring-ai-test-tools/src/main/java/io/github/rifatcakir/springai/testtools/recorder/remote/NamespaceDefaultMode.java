package io.github.rifatcakir.springai.testtools.recorder.remote;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.github.rifatcakir.springai.testtools.recorder.VcrMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.Assert;

/**
 * The mode for an application that configures none, read from its Cassette Server
 * namespace (PRD fork b): the client decides, and this is only what it decides when the
 * application left the choice to the environment.
 *
 * <p>Asked on every call, not once at startup. The point of a namespace default is that an
 * admin can switch a whole environment to {@code RECORD_ALWAYS} and back with one request;
 * caching it would turn that into "and then restart every application". On a test network
 * the extra request costs a few milliseconds, the same trade the MVP already makes by
 * having no local fixture cache (fork c). An application that sets {@code
 * spring.ai.test.vcr.mode} never uses this and never makes the request.
 *
 * <p>An unreachable server or an unknown mode name is a {@link CassetteServerException},
 * like every other server failure: falling back to some mode would be a guess, and a wrong
 * guess in a sealed {@code REPLAY_ONLY} environment means calling the real model.
 *
 * @author Rifat Cakir
 */
public class NamespaceDefaultMode implements Supplier<VcrMode> {

	private static final Logger logger = LoggerFactory.getLogger(NamespaceDefaultMode.class);

	private final CassetteServerClient client;

	private final AtomicReference<VcrMode> lastSeen = new AtomicReference<>();

	public NamespaceDefaultMode(CassetteServerClient client) {
		Assert.notNull(client, "client must not be null");
		this.client = client;
	}

	@Override
	public VcrMode get() {
		String name = this.client.namespaceMode();
		VcrMode mode;
		try {
			mode = VcrMode.valueOf(name.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new CassetteServerException("Cassette Server namespace '" + this.client.getNamespace()
					+ "' reports mode '" + name + "', which this library version does not know. Set "
					+ "spring.ai.test.vcr.mode explicitly, or align the server's namespace mode.", ex);
		}
		VcrMode previous = this.lastSeen.getAndSet(mode);
		if (previous != mode) {
			logger.info("VCR mode is {} — the default of Cassette Server namespace '{}' (spring.ai.test.vcr.mode is "
					+ "not set)", mode, this.client.getNamespace());
		}
		return mode;
	}

	@Override
	public String toString() {
		return "the default of Cassette Server namespace '" + this.client.getNamespace() + "'";
	}

}
