package io.github.rifatcakir.springai.testtools.recorder.remote;

/**
 * The Cassette Server could not be used: unreachable, timed out, answered {@code 5xx},
 * refused the token, or refused a fixture.
 *
 * <p>Thrown rather than reported as a cache miss, deliberately (PRD fork a). A miss in
 * {@code RECORD_OR_REPLAY} calls the real model, so treating "server down" as a miss
 * would make a shared test environment silently start spending tokens and returning
 * fresh, non-deterministic answers whenever the server blips: exactly the flakiness the
 * library exists to remove. Nor is there a fallback to local files: an application in a
 * deployed environment has none worth replaying, and a quiet fallback would hide the
 * outage. The message always names the server URL and namespace, since the person
 * reading it is usually not the one who configured them.
 *
 * @author Rifat Cakir
 */
public class CassetteServerException extends RuntimeException {

	public CassetteServerException(String message) {
		super(message);
	}

	public CassetteServerException(String message, Throwable cause) {
		super(message, cause);
	}

}
