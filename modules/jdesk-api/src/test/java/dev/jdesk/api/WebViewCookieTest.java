package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebViewCookieTest {
    @Test
    void buildsSessionAndPersistentCookiesWithStableKeys() {
        WebViewCookie session = WebViewCookie.session(
                "sid", "abc", ".example.com", "/account", true, true);
        WebViewCookie persistent = new WebViewCookie(
                "theme", "dark", "example.com", "/", Optional.of(Instant.EPOCH), false, false);

        assertThat(session.expiresAt()).isEmpty();
        assertThat(session.key()).isEqualTo(
                new WebViewCookieKey("sid", ".example.com", "/account"));
        assertThat(persistent.expiresAt()).contains(Instant.EPOCH);
    }

    @Test
    void rejectsCookieHeaderInjectionAndInvalidIdentityParts() {
        assertThatThrownBy(() -> WebViewCookie.session(
                "bad name", "value", "example.com", "/", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebViewCookie.session(
                "name", "bad;value", "example.com", "/", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebViewCookie.session(
                "name", "bad value", "example.com", "/", false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebViewCookieKey("name", "https://example.com", "/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebViewCookieKey("name", "example.com", "relative"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
