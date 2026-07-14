package dev.jdesk.plugin;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Deny-by-default capability gate for plugins, modelled on Tauri's permission ACL: merely adding a
 * plugin grants it nothing. Every capability a plugin's {@link PluginManifest} declares must be in
 * the set the application explicitly granted that plugin, or it is refused before loading.
 */
public final class PluginAuthorization {

    private PluginAuthorization() {
    }

    /** Capabilities the plugin declared that the app did not grant (empty ⇒ authorized). */
    public static Set<String> ungrantedCapabilities(PluginManifest manifest,
            Set<String> grantedCapabilities) {
        Set<String> missing = new LinkedHashSet<>(manifest.capabilities());
        missing.removeAll(grantedCapabilities);
        return Set.copyOf(missing);
    }

    /** True when every capability the plugin needs has been granted to it. */
    public static boolean isAuthorized(PluginManifest manifest, Set<String> grantedCapabilities) {
        return ungrantedCapabilities(manifest, grantedCapabilities).isEmpty();
    }

    /**
     * Enforces the grant: a no-op when authorized, otherwise a {@link PluginSecurityException}
     * naming the ungranted capabilities so the app author can decide to grant or refuse them.
     */
    public static void authorize(PluginManifest manifest, Set<String> grantedCapabilities) {
        Set<String> missing = ungrantedCapabilities(manifest, grantedCapabilities);
        if (!missing.isEmpty()) {
            throw new PluginSecurityException("Plugin " + manifest.pluginId()
                    + " requires capabilities the app did not grant: " + missing);
        }
    }
}
