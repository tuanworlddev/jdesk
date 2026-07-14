package dev.jdesk.webview.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The shared console-capture user script has the load-bearing pieces. */
class InitScriptsTest {

    @Test
    void consoleCaptureInstallsErrorListenersInCapturePhase() {
        String script = InitScripts.CONSOLE_CAPTURE;
        assertThat(script).isNotBlank();
        // Capture phase (third arg true) is what lets resource-load failures be seen.
        assertThat(script).contains("addEventListener(\"error\"");
        assertThat(script).contains("}, true)");
        assertThat(script).contains("unhandledrejection");
        // The rejection forwarder respects event.preventDefault() from an app listener.
        assertThat(script).contains("defaultPrevented");
        // Early failures are buffered until the nonce arrives, then shipped.
        assertThat(script).contains("queue.push");
        assertThat(script).contains("kind: \"console\"");
        // Resource-target failures report the failing URL.
        assertThat(script).contains("Failed to load");
    }
}
