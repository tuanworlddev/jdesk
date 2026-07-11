package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PlatformInfo;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformProvider;
import java.util.List;
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
}
