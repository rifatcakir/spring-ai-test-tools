package io.github.rifatcakir.springai.testtools.recorder;

/**
 * Where fixtures live, chosen with {@code spring.ai.test.vcr.store}.
 *
 * @author Rifat Cakir
 */
public enum VcrStoreType {

	/**
	 * JSON files under the configured cache directories. The default, and the right
	 * choice for unit and CI tests whose fixtures are committed with the code.
	 */
	FILE,

	/**
	 * A Cassette Server ({@code spring.ai.test.vcr.remote.*}), for deployed test
	 * environments and fixtures shared between instances or services. Same fixtures,
	 * same format; only the location moves.
	 */
	REMOTE

}
