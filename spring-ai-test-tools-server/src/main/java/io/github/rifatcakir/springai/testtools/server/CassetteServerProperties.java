package io.github.rifatcakir.springai.testtools.server;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server settings, bound from {@code cassette.*}.
 *
 * <p>The documented command-line flags ({@code --port}, {@code --storage-dir}, {@code
 * --admin-token}, {@code --client-token}) are mapped onto these in {@code
 * application.properties}, so {@code java -jar … --storage-dir=/data} reads the way the
 * PRD and README show it, while {@code CASSETTE_STORAGE_DIR} and every other Spring Boot
 * property source keep working too.
 *
 * @author Rifat Cakir
 */
@ConfigurationProperties(prefix = "cassette")
public class CassetteServerProperties {

	/**
	 * Directory holding one subdirectory per namespace, in the library's file layout.
	 */
	private Path storageDir = Path.of("cassettes");

	/**
	 * Bearer token required on every {@code /__admin} request. When unset, the admin API
	 * is disabled outright rather than open: listing, deleting and exporting recorded
	 * prompts is not something to leave unauthenticated by accident.
	 */
	private @Nullable String adminToken;

	/**
	 * Optional bearer token required on the client API for every namespace that has no
	 * entry in {@link #namespaceTokens}. Unset means the client API is open, the expected
	 * setup on an internal test network.
	 */
	private @Nullable String clientToken;

	/**
	 * Per-namespace client API tokens, overriding {@link #clientToken} for that namespace.
	 */
	private Map<String, String> namespaceTokens = new LinkedHashMap<>();

	public Path getStorageDir() {
		return this.storageDir;
	}

	public void setStorageDir(Path storageDir) {
		this.storageDir = storageDir;
	}

	public @Nullable String getAdminToken() {
		return this.adminToken;
	}

	public void setAdminToken(@Nullable String adminToken) {
		this.adminToken = adminToken;
	}

	public @Nullable String getClientToken() {
		return this.clientToken;
	}

	public void setClientToken(@Nullable String clientToken) {
		this.clientToken = clientToken;
	}

	public Map<String, String> getNamespaceTokens() {
		return this.namespaceTokens;
	}

	public void setNamespaceTokens(Map<String, String> namespaceTokens) {
		this.namespaceTokens = namespaceTokens;
	}

}
