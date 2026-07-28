package io.github.rifatcakir.springai.testtools.build.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildCoreModuleTests {

    @Test
    void isNotInstantiable() {
        assertThat(BuildCoreModule.class.getDeclaredConstructors()).hasSize(1);
        assertThat(BuildCoreModule.class.getDeclaredConstructors()[0].canAccess(null)).isFalse();
    }
}
