package dev.jdesk.gradle.internal;

import java.util.Locale;

/** Execution-time OS detection helpers for task actions. */
public final class OsSupport {
    private OsSupport() {
    }

    public static String osName() {
        return System.getProperty("os.name", "unknown");
    }

    public static String osArch() {
        return System.getProperty("os.arch", "unknown");
    }

    public static boolean isMacOs() {
        return osName().toLowerCase(Locale.ROOT).contains("mac");
    }

    public static boolean isWindows() {
        return osName().toLowerCase(Locale.ROOT).contains("windows");
    }

    public static boolean isLinux() {
        return osName().toLowerCase(Locale.ROOT).contains("linux");
    }
}
