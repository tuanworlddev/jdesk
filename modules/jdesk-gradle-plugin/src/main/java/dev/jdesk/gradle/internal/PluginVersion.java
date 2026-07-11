package dev.jdesk.gradle.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Reads the plugin's own version from a resource generated at build time. */
public final class PluginVersion {
    private static final String RESOURCE = "/dev/jdesk/gradle/jdesk-plugin-version.properties";
    private static final String FALLBACK = "0.1.0-SNAPSHOT";

    private PluginVersion() {
    }

    public static String get() {
        try (InputStream in = PluginVersion.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return version.strip();
                }
            }
        } catch (IOException ignored) {
            // fall through to the fallback
        }
        return FALLBACK;
    }
}
