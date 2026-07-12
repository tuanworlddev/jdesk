package dev.jdesk.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;

/**
 * Deep-link configuration under {@code jdesk { deepLink { ... } }}:
 *
 * <pre>{@code
 * jdesk {
 *     deepLink {
 *         schemes.add("jdesk-forge")
 *         usageDescription("NSDesktopFolderUsageDescription", "Open dropped files")
 *     }
 * }
 * }</pre>
 *
 * The packaging task registers these {@code scheme://} URL schemes with the OS so it routes
 * matching links to the app: macOS {@code Info.plist} {@code CFBundleURLTypes} (Launch Services),
 * a Linux {@code .desktop} {@code x-scheme-handler/<scheme>} entry, and a Windows HKCU
 * {@code URL Protocol} {@code .reg} script. Usage descriptions apply to macOS only.
 */
public abstract class JDeskDeepLinkExtension {

    /** {@code scheme://} URL schemes to register (without the {@code ://}). */
    public abstract ListProperty<String> getSchemes();

    /** Extra {@code Info.plist} keys (e.g. {@code NS*UsageDescription}) → string values. */
    public abstract MapProperty<String, String> getUsageDescriptions();

    /** Convenience for {@code getUsageDescriptions().put(key, value)}. */
    public void usageDescription(String key, String value) {
        getUsageDescriptions().put(key, value);
    }
}
