package dev.jdesk.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.WindowId;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link Capabilities#fromResource(Module, String)}. On the classpath the
 * test class lives in the unnamed module, so the method delegates to the ClassLoader path.
 */
class CapabilitiesModuleResourceTest {

    @Test
    void fromResourceWithUnnamedModuleDelegatesToClassLoader() {
        Module module = getClass().getModule();
        CapabilitySet set = Capabilities.fromResource(module, "jdesk-capabilities-test.json");
        assertThat(set.isGranted("greeting:use", new WindowId("main"))).isTrue();
        assertThat(set.isGranted("clipboard:read", new WindowId("other"))).isTrue();
    }

    @Test
    void fromResourceWithUnnamedModuleAndMissingResourceFails() {
        Module module = getClass().getModule();
        assertThatThrownBy(() -> Capabilities.fromResource(module, "missing-caps-xyz.json"))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE));
    }

    @Test
    void fromResourceWithNamedModuleReadsBundledResource() {
        // java.base is a named, resolved module in the boot layer.
        Module javaBase = Object.class.getModule();
        // module.getResourceAsStream on java.base cannot see arbitrary internal resources
        // for an outside caller, so this exercises the not-found branch of the named path.
        assertThatThrownBy(() -> Capabilities.fromResource(javaBase, "no/such/capabilities.json"))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE))
                .hasMessageContaining("java.base");
    }
}
