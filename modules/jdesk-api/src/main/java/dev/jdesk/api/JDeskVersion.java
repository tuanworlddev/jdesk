package dev.jdesk.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Framework and IPC protocol version information embedded at build time. */
public final class JDeskVersion {
    /** Independent integer IPC protocol version (spec section 25). */
    public static final int PROTOCOL_VERSION = 1;
    private static final String CURRENT = loadVersion();

    private JDeskVersion() {
    }

    /** Exact framework build version, or {@code development} for an incomplete classpath. */
    public static String current() {
        return CURRENT;
    }

    private static String loadVersion() {
        try (InputStream input = JDeskVersion.class.getResourceAsStream(
                "/dev/jdesk/api/jdesk-version.properties")) {
            if (input == null) {
                return "development";
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version", "development");
        } catch (IOException e) {
            return "development";
        }
    }
}
