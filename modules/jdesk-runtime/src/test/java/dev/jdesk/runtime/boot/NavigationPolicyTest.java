package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jdesk.webview.spi.NavigationDecision;
import dev.jdesk.webview.spi.NavigationRequest;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Strict navigation policy (spec section 12.2): main-frame navigation restricted to
 * allowed origins, remote main-frame denied by default, subframes allowed (they get no
 * native authority).
 */
class NavigationPolicyTest {

    private final NavigationPolicy policy =
            new NavigationPolicy(Set.of("jdesk://app", "http://localhost:5173"));

    private static NavigationRequest mainFrame(String uri) {
        return new NavigationRequest(URI.create(uri), true, false);
    }

    private static NavigationRequest subFrame(String uri) {
        return new NavigationRequest(URI.create(uri), false, false);
    }

    @Test
    void mainFrameToAppOriginIsAllowed() {
        assertThat(policy.decide(mainFrame("jdesk://app/index.html")))
                .isEqualTo(NavigationDecision.ALLOW);
        assertThat(policy.decide(mainFrame("jdesk://app/other/page.html")))
                .isEqualTo(NavigationDecision.ALLOW);
    }

    @Test
    void remoteMainFrameIsBlocked() {
        assertThat(policy.decide(mainFrame("https://evil.example/phish")))
                .isEqualTo(NavigationDecision.BLOCK);
        assertThat(policy.decide(mainFrame("http://example.com/")))
                .isEqualTo(NavigationDecision.BLOCK);
    }

    @Test
    void subframeRemoteLoadIsAllowed() {
        assertThat(policy.decide(subFrame("https://embedded.example/widget")))
                .isEqualTo(NavigationDecision.ALLOW);
    }

    @Test
    void unparseableTargetIsBlocked() {
        // No authority component: not attributable to any origin.
        assertThat(policy.decide(mainFrame("about:blank"))).isEqualTo(NavigationDecision.BLOCK);
        assertThat(policy.decide(mainFrame("/relative/path"))).isEqualTo(NavigationDecision.BLOCK);
        assertThat(policy.decide(mainFrame("data:text/html;base64,PGgxPng8L2gxPg==")))
                .isEqualTo(NavigationDecision.BLOCK);
    }

    @Test
    void devOriginIsAllowedWhenConfigured() {
        assertThat(policy.decide(mainFrame("http://localhost:5173/page")))
                .isEqualTo(NavigationDecision.ALLOW);
        // Same host, different port: not the configured dev origin.
        assertThat(policy.decide(mainFrame("http://localhost:5174/page")))
                .isEqualTo(NavigationDecision.BLOCK);
    }

    @Test
    void devOriginComparisonIsNormalized() {
        assertThat(policy.decide(mainFrame("HTTP://LOCALHOST:5173/page")))
                .isEqualTo(NavigationDecision.ALLOW);
    }

    @Test
    void policyWithoutDevOriginBlocksLocalhost() {
        NavigationPolicy production = new NavigationPolicy(Set.of("jdesk://app"));
        assertThat(production.decide(mainFrame("http://localhost:5173/page")))
                .isEqualTo(NavigationDecision.BLOCK);
    }
}
