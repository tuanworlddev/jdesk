package dev.jdesk.webview.spi;

import java.util.Objects;
import java.util.Optional;

/**
 * Platform-level application configuration.
 *
 * @param applicationId reverse-DNS application id
 * @param devMode true only in development; enables DevTools and the dev origin
 * @param devServerOrigin exact allowed development origin, present only in dev mode
 * @param assetHandler serves {@code jdesk://app/} requests; platform adapters call it
 *        from their scheme-interception API, never from a socket
 */
public record PlatformApplicationConfig(
        String applicationId,
        boolean devMode,
        Optional<String> devServerOrigin,
        AssetHandler assetHandler) {

    public PlatformApplicationConfig {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(devServerOrigin, "devServerOrigin");
        Objects.requireNonNull(assetHandler, "assetHandler");
        if (!devMode && devServerOrigin.isPresent()) {
            throw new IllegalArgumentException("devServerOrigin requires devMode");
        }
    }
}
