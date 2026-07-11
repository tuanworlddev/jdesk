package dev.jdesk.webview.spi;

import dev.jdesk.api.PlatformInfo;

/**
 * Entry point implemented by each platform adapter, discovered via JPMS
 * {@link java.util.ServiceLoader}. A packaged application contains exactly one provider;
 * startup fails with a diagnostic for zero or multiple providers.
 */
public interface PlatformProvider {
    /**
     * Stable provider id, e.g. {@code windows-webview2}, {@code macos-wkwebview},
     * {@code linux-webkitgtk}. Evidence verification rejects {@code fake}, {@code mock},
     * and {@code headless-fake} ids for native results.
     */
    String id();

    PlatformInfo info();

    PlatformApplication createApplication(PlatformApplicationConfig config);
}
