package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.CapabilitySet;
import dev.jdesk.api.CommandRegistry;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.assets.CspValidator;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exactly-one-provider rule (spec section 8): zero or multiple platform providers fail
 * startup with a diagnostic naming what was found.
 */
class RuntimeBootstrapTest {

    private static PlatformProvider provider(String id) {
        return new PlatformProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public PlatformInfo info() {
                return new PlatformInfo("test", "0", "test");
            }

            @Override
            public PlatformApplication createApplication(PlatformApplicationConfig config) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
    }

    @Test
    void zeroProvidersFailsWithFoundNone() {
        assertThatThrownBy(() -> RuntimeBootstrap.selectProvider(List.of()))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE))
                .hasMessageContaining("found: none");
    }

    @Test
    void multipleProvidersFailsListingTheirIds() {
        assertThatThrownBy(() -> RuntimeBootstrap.selectProvider(
                List.of(provider("windows-webview2"), provider("macos-wkwebview"))))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE))
                .hasMessageContaining("windows-webview2")
                .hasMessageContaining("macos-wkwebview");
    }

    @Test
    void singleProviderIsReturned() {
        PlatformProvider only = provider("macos-wkwebview");
        assertThat(RuntimeBootstrap.selectProvider(List.of(only))).isSameAs(only);
    }

    private static ApplicationSpec spec(Optional<String> csp) {
        return new ApplicationSpec("dev.jdesk.example", CommandRegistry.of(),
                CapabilitySet.empty(),
                List.of(WindowConfig.builder().id("main").entry("jdesk://app/index.html").build()),
                List.of(), Optional.empty(), CommandRegistry.of(), false, ignored -> { }, csp);
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty("jdesk.dev");
        System.clearProperty("jdesk.security.acknowledgeUnsafeCsp");
    }

    @Test
    void optionsForKeepsDefaultCspWhenSpecHasNoOverride() {
        RuntimeOptions options = RuntimeBootstrap.optionsFor(spec(Optional.empty()));
        assertThat(options.securityHeaders())
                .containsEntry("Content-Security-Policy", CspValidator.DEFAULT_CSP);
    }

    @Test
    void optionsForAppliesCspOverride() {
        String csp = "default-src 'self'; media-src 'self' https:; img-src 'self' data: https:";
        RuntimeOptions options = RuntimeBootstrap.optionsFor(spec(Optional.of(csp)));
        assertThat(options.securityHeaders()).containsEntry("Content-Security-Policy", csp);
    }

    @Test
    void optionsForRejectsUnsafeCspInProductionWithoutAcknowledgement() {
        assertThatThrownBy(() -> RuntimeBootstrap.optionsFor(
                spec(Optional.of("default-src 'self'; script-src 'self' 'unsafe-inline'"))))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE))
                .hasMessageContaining("acknowledgement");
    }

    @Test
    void optionsForAllowsUnsafeCspWithAcknowledgement() {
        System.setProperty("jdesk.security.acknowledgeUnsafeCsp", "true");
        String csp = "default-src 'self'; script-src 'self' 'unsafe-inline'";
        RuntimeOptions options = RuntimeBootstrap.optionsFor(spec(Optional.of(csp)));
        assertThat(options.securityHeaders()).containsEntry("Content-Security-Policy", csp);
    }

    @Test
    void optionsForSkipsReleaseScreeningInDevMode() {
        System.setProperty("jdesk.dev", "true");
        String csp = "default-src 'self'; script-src 'self' 'unsafe-eval'";
        RuntimeOptions options = RuntimeBootstrap.optionsFor(spec(Optional.of(csp)));
        assertThat(options.securityHeaders()).containsEntry("Content-Security-Policy", csp);
    }
}
