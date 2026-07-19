package io.github.rifatcakira.springai.vcr.autoconfigure;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.rifatcakira.springai.vcr.VcrMode;
import io.github.rifatcakira.springai.vcr.VcrPromptNormalizer;
import io.github.rifatcakira.springai.vcr.advisor.DeterministicVcrAdvisor;
import io.github.rifatcakira.springai.vcr.key.VcrCacheKey;
import io.github.rifatcakira.springai.vcr.key.VcrCacheKeyGenerator;
import io.github.rifatcakira.springai.vcr.track.VcrTrackMapper;
import io.github.rifatcakira.springai.vcr.track.VcrTrackStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice tests for {@link SpringAiVcrAutoConfiguration}.
 *
 * <p>Unlike {@code DeterministicVcrAdvisorTests}, which mocks the chain to prove the
 * advisor's cache behaviour, these tests prove the wiring around it: that nothing is
 * registered unless explicitly enabled, that the advisor lands at the order its scope
 * implies, that an explicit order wins over the scope-derived one, that a user-supplied
 * bean of any of the four types silences the auto-configured default, and that
 * registered {@link VcrPromptNormalizer} beans actually reach the generated
 * {@link VcrCacheKeyGenerator}.
 *
 * @author Rifat Cakira
 */
class SpringAiVcrAutoConfigurationTests {

	@TempDir
	Path cacheDirectory;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(SpringAiVcrAutoConfiguration.class));

	private String cacheDirectoryProperty() {
		return "spring.ai.test.vcr.cache-directory=" + this.cacheDirectory;
	}

	@Test
	@DisplayName("nothing is registered when the enabled property is absent")
	void absentWhenPropertyMissing() {
		this.contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(DeterministicVcrAdvisor.class);
			assertThat(context).doesNotHaveBean(VcrTrackStore.class);
			assertThat(context).doesNotHaveBean(VcrCacheKeyGenerator.class);
			assertThat(context).doesNotHaveBean(VcrTrackMapper.class);
			assertThat(context).doesNotHaveBean(ChatClientBuilderCustomizer.class);
		});
	}

	@Test
	@DisplayName("nothing is registered when the enabled property is explicitly false")
	void absentWhenPropertyFalse() {
		this.contextRunner.withPropertyValues("spring.ai.test.vcr.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(DeterministicVcrAdvisor.class);
			assertThat(context).doesNotHaveBean(ChatClientBuilderCustomizer.class);
		});
	}

	@Test
	@DisplayName("every collaborator is registered when enabled")
	void allBeansPresentWhenEnabled() {
		this.contextRunner.withPropertyValues("spring.ai.test.vcr.enabled=true", cacheDirectoryProperty())
			.run(context -> {
				assertThat(context).hasSingleBean(VcrCacheKeyGenerator.class);
				assertThat(context).hasSingleBean(VcrTrackMapper.class);
				assertThat(context).hasSingleBean(VcrTrackStore.class);
				assertThat(context).hasSingleBean(DeterministicVcrAdvisor.class);
				assertThat(context).hasSingleBean(ChatClientBuilderCustomizer.class);
			});
	}

	@Test
	@DisplayName("default scope (OUTSIDE_TOOL_LOOP) places the advisor before ToolCallingAdvisor")
	void orderDefaultsToOutsideToolLoopScope() {
		this.contextRunner.withPropertyValues("spring.ai.test.vcr.enabled=true", cacheDirectoryProperty())
			.run(context -> assertThat(context.getBean(DeterministicVcrAdvisor.class).getOrder())
				.isEqualTo(DeterministicVcrAdvisor.ORDER_OUTSIDE_TOOL_LOOP));
	}

	@Test
	@DisplayName("scope=INSIDE_TOOL_LOOP places the advisor after ToolCallingAdvisor")
	void insideToolLoopScopeChangesOrder() {
		this.contextRunner
			.withPropertyValues("spring.ai.test.vcr.enabled=true", cacheDirectoryProperty(),
					"spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP")
			.run(context -> assertThat(context.getBean(DeterministicVcrAdvisor.class).getOrder())
				.isEqualTo(DeterministicVcrAdvisor.ORDER_INSIDE_TOOL_LOOP));
	}

	@Test
	@DisplayName("an explicit order overrides the scope-derived value")
	void explicitOrderOverridesScope() {
		this.contextRunner
			.withPropertyValues("spring.ai.test.vcr.enabled=true", cacheDirectoryProperty(),
					"spring.ai.test.vcr.scope=INSIDE_TOOL_LOOP", "spring.ai.test.vcr.order=12345")
			.run(context -> assertThat(context.getBean(DeterministicVcrAdvisor.class).getOrder()).isEqualTo(12345));
	}

	@Test
	@DisplayName("RECORD_ALWAYS is honoured as the configured mode")
	void modeIsBoundFromProperties() {
		// mode is not exposed on the advisor; the closest observable proof it took effect
		// is that the property binds without error and the advisor still starts up.
		this.contextRunner
			.withPropertyValues("spring.ai.test.vcr.enabled=true", cacheDirectoryProperty(),
					"spring.ai.test.vcr.mode=RECORD_ALWAYS")
			.run(context -> assertThat(context).hasSingleBean(DeterministicVcrAdvisor.class));
	}

	@Test
	@DisplayName("a user-supplied bean of each type wins over the auto-configured default")
	void userSuppliedBeansOverrideDefaults() {
		this.contextRunner.withUserConfiguration(CustomBeansConfiguration.class)
			.withPropertyValues("spring.ai.test.vcr.enabled=true", cacheDirectoryProperty())
			.run(context -> {
				assertThat(context.getBean(VcrCacheKeyGenerator.class)).isSameAs(CustomBeansConfiguration.KEY_GENERATOR);
				assertThat(context.getBean(VcrTrackMapper.class)).isSameAs(CustomBeansConfiguration.MAPPER);
				assertThat(context.getBean(VcrTrackStore.class)).isSameAs(CustomBeansConfiguration.STORE);
				assertThat(context.getBean(DeterministicVcrAdvisor.class)).isSameAs(CustomBeansConfiguration.ADVISOR);
				// The advisor order is the tell that @ConditionalOnMissingBean actually
				// worked, rather than the auto-configured advisor happening to be equal.
				assertThat(context.getBean(DeterministicVcrAdvisor.class).getOrder()).isEqualTo(CustomBeansConfiguration.CUSTOM_ORDER);
			});
	}

	@Test
	@DisplayName("registered VcrPromptNormalizer beans reach the generated VcrCacheKeyGenerator")
	void promptNormalizersReachTheKeyGenerator() {
		this.contextRunner.withUserConfiguration(RedactingNormalizerConfiguration.class)
			.withPropertyValues("spring.ai.test.vcr.enabled=true", cacheDirectoryProperty())
			.run(context -> {
				VcrCacheKeyGenerator keyGenerator = context.getBean(VcrCacheKeyGenerator.class);
				VcrCacheKey key = keyGenerator.generate(new Prompt(List.of(new UserMessage("my SECRET value"))));

				assertThat(key.canonicalRequest()).doesNotContain("SECRET").contains("***");
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomBeansConfiguration {

		static final int CUSTOM_ORDER = 987654;

		static final VcrCacheKeyGenerator KEY_GENERATOR = new VcrCacheKeyGenerator();

		static final VcrTrackMapper MAPPER = new VcrTrackMapper(List.of());

		static final AtomicReference<VcrTrackStore> STORE_HOLDER = new AtomicReference<>();

		static final VcrTrackStore STORE = createStore();

		static final DeterministicVcrAdvisor ADVISOR = new DeterministicVcrAdvisor(KEY_GENERATOR, STORE, MAPPER,
				VcrMode.RECORD_OR_REPLAY, CUSTOM_ORDER);

		private static VcrTrackStore createStore() {
			// Never written to in this test, so an unused path is fine — VcrTrackStore does
			// not touch the filesystem until read()/write() is called.
			VcrTrackStore store = new VcrTrackStore(Path.of(System.getProperty("java.io.tmpdir"), "vcr-custom-bean-test"));
			STORE_HOLDER.set(store);
			return store;
		}

		@Bean
		VcrCacheKeyGenerator vcrCacheKeyGenerator() {
			return KEY_GENERATOR;
		}

		@Bean
		VcrTrackMapper vcrTrackMapper() {
			return MAPPER;
		}

		@Bean
		VcrTrackStore vcrTrackStore() {
			return STORE;
		}

		@Bean
		DeterministicVcrAdvisor deterministicVcrAdvisor() {
			return ADVISOR;
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class RedactingNormalizerConfiguration {

		@Bean
		VcrPromptNormalizer secretRedactor() {
			return text -> text.replace("SECRET", "***");
		}

	}

}
