package dev.jdesk.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Locks security-sensitive slots reviewed against WebView2 SDK 1.0.2903.40. */
class WebView2VtableConstantsTest {
    @Test
    void settingsSlotsMatchReviewedSdkHeader() {
        assertThat(WebView2.SETTINGS_GET_ARE_DEV_TOOLS_ENABLED).isEqualTo(11);
        assertThat(WebView2.SETTINGS_PUT_ARE_DEV_TOOLS_ENABLED).isEqualTo(12);
        assertThat(WebView2.SETTINGS_PUT_ARE_DEFAULT_CONTEXT_MENUS_ENABLED).isEqualTo(14);
        assertThat(WebView2.SETTINGS2_PUT_USER_AGENT).isEqualTo(22);
        assertThat(WebView2.IID_SETTINGS2)
                .isEqualTo("ee9a0f68-f46c-4e32-ac23-ef8cac224d2a");
    }
}
