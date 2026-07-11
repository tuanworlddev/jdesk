package dev.jdesk.webview.spi;

/**
 * Serves {@code jdesk://app/} resource requests. Implemented by the runtime asset
 * resolver; called by platform adapters from their documented scheme-interception APIs.
 * Never backed by a socket or HTTP listener in production.
 */
@FunctionalInterface
public interface AssetHandler {
    AssetResponse handle(AssetRequest request);
}
