package dev.jdesk.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure-logic test for the JSON-string unwrap that aligns evaluate() with WKWebView/WebKitGTK. */
class WindowsWebViewUnwrapTest {

    @Test
    void unwrapsTopLevelJsonString() {
        assertThat(WindowsWebView.unwrapJsonString("\"left\"")).isEqualTo("left");
        assertThat(WindowsWebView.unwrapJsonString("\"alive\"")).isEqualTo("alive");
        assertThat(WindowsWebView.unwrapJsonString("\"\"")).isEmpty();
    }

    @Test
    void decodesJsonEscapes() {
        assertThat(WindowsWebView.unwrapJsonString("\"a\\nb\"")).isEqualTo("a\nb");
        assertThat(WindowsWebView.unwrapJsonString("\"a\\\"b\"")).isEqualTo("a\"b");
        assertThat(WindowsWebView.unwrapJsonString("\"\\u0041\"")).isEqualTo("A");
        assertThat(WindowsWebView.unwrapJsonString("\"c:\\\\x\"")).isEqualTo("c:\\x");
    }

    @Test
    void leavesNonStringJsonUnchanged() {
        assertThat(WindowsWebView.unwrapJsonString("42")).isEqualTo("42");
        assertThat(WindowsWebView.unwrapJsonString("true")).isEqualTo("true");
        assertThat(WindowsWebView.unwrapJsonString("null")).isEqualTo("null");
        assertThat(WindowsWebView.unwrapJsonString("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(WindowsWebView.unwrapJsonString("")).isEmpty();
        assertThat(WindowsWebView.unwrapJsonString(null)).isNull();
    }
}
