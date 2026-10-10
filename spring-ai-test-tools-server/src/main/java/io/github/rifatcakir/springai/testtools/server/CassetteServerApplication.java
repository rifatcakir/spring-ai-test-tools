package io.github.rifatcakir.springai.testtools.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The Cassette Server: stores the library's fixtures for every application in an
 * environment, by namespace, kind and hash. See {@code docs/SERVER-PRD.md}.
 *
 * <p>Deliberately dumb. It never calls a model provider, holds no provider key and has
 * no Spring AI dependency: the client computes the hash, decides hit or miss, calls the
 * model and redacts, and this process only keeps the documents. That split is what keeps
 * every design rule of the library intact while fixtures move off the local disk.
 *
 * @author Rifat Cakir
 */
@SpringBootApplication
@EnableConfigurationProperties(CassetteServerProperties.class)
public class CassetteServerApplication implements WebMvcConfigurer {

	private static final Logger logger = LoggerFactory.getLogger(CassetteServerApplication.class);

	private final CassetteServerProperties properties;

	private final JsonMapper jsonMapper;

	public CassetteServerApplication(CassetteServerProperties properties, JsonMapper jsonMapper) {
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	public static void main(String[] args) {
		SpringApplication.run(CassetteServerApplication.class, args);
	}

	@Bean
	public FixtureValidator fixtureValidator() {
		return new FixtureValidator(this.jsonMapper);
	}

	@Bean
	public FixtureStorage fixtureStorage(FixtureValidator validator) {
		FixtureStorage storage = new FixtureStorage(this.properties.getStorageDir(), validator, this.jsonMapper);
		logger.info("CASSETTE storage directory: {}", storage.getRoot());
		if (!StringUtils.hasText(this.properties.getAdminToken())) {
			logger.info("CASSETTE admin API disabled (no --admin-token)");
		}
		if (StringUtils.hasText(this.properties.getClientToken()) || !this.properties.getNamespaceTokens().isEmpty()) {
			logger.info("CASSETTE client API requires a bearer token");
		}
		return storage;
	}

	@Bean
	public NamespaceModes namespaceModes(FixtureStorage storage) {
		return new NamespaceModes(storage.getRoot(), this.jsonMapper, this.properties.getDefaultMode());
	}

	@Bean
	public MissLog missLog() {
		return new MissLog(this.properties.getMissesPerNamespace());
	}

	@Bean
	public NamespaceExport namespaceExport(FixtureStorage storage) {
		return new NamespaceExport(storage);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AccessTokenInterceptor(this.properties, this.jsonMapper))
			.addPathPatterns("/api/v1/**", "/__admin/**");
	}

}
