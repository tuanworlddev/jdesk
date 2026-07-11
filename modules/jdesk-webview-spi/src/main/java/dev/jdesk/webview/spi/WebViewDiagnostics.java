package dev.jdesk.webview.spi;

import java.util.Optional;

/**
 * Diagnostic facts exposed for crash reports and evidence manifests. Must not contain
 * message payloads or secrets.
 */
public record WebViewDiagnostics(
        Optional<String> engineVersion,
        Optional<String> userAgent,
        Optional<Long> webViewProcessId) {
}
