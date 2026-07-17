package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebViewSessionConfigTest {
    @Test
    void buildsPersistentAndPrivateSessions() {
        WebViewSessionConfig persistent = WebViewSessionConfig.persistent("account-a")
                .userAgent("JDesk-Test/1")
                .build();
        WebViewSessionConfig privateSession = WebViewSessionConfig.privateSession("private").build();

        assertThat(persistent.storage()).isEqualTo(WebViewSessionConfig.Storage.PERSISTENT);
        assertThat(persistent.userAgent()).contains("JDesk-Test/1");
        assertThat(privateSession.storage()).isEqualTo(WebViewSessionConfig.Storage.PRIVATE);
    }

    @Test
    void rejectsUnsafeIdsAndUserAgents() {
        assertThatThrownBy(() -> WebViewSessionConfig.persistent("../escape").build())
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> WebViewSessionConfig.persistent("ok")
                .userAgent("bad\r\nheader").build())
                .isInstanceOf(JDeskException.class);
    }

    @Test
    void windowBuilderCarriesSessionConfiguration() {
        WebViewSessionConfig session = WebViewSessionConfig.privateSession("login").build();
        WindowConfig window = WindowConfig.builder()
                .id("main")
                .entry("jdesk://app/index.html")
                .webViewSession(session)
                .build();

        assertThat(window.webViewSession()).isEqualTo(session);
        assertThat(WindowConfig.builder().id("other").entry("jdesk://app/index.html")
                .build().webViewSession()).isEqualTo(WebViewSessionConfig.DEFAULT);
    }
}
