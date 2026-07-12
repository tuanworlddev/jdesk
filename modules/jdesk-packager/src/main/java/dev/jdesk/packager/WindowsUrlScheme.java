package dev.jdesk.packager;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds a Windows {@code .reg} script that registers {@code scheme://} deep links under
 * {@code HKEY_CURRENT_USER\Software\Classes} (per-user, no admin), the counterpart to
 * {@link InfoPlistCustomizer} on macOS. Each scheme gets the {@code URL Protocol} marker and a
 * {@code shell\open\command} pointing at the launcher with {@code "%1"} so Windows passes the
 * activated URL as an argument (which JDesk's single-instance layer forwards to the app's
 * activation handler).
 *
 * <p>Pure text generation — no registry or OS calls, so it is unit-testable on any host. jpackage
 * exposes no URL-scheme flag (only file associations), so this script is emitted next to the app
 * image; applying it ({@code reg import}, an installer custom action, or first-run) is the Windows
 * analogue of macOS Launch Services reading {@code Info.plist}. Because it targets HKCU it is
 * user-scoped and reversible.
 */
public final class WindowsUrlScheme {

    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*");

    private WindowsUrlScheme() {
    }

    /**
     * @param schemes {@code scheme://} names (without {@code ://}); must be non-empty and valid
     * @param command the launcher command (e.g. the absolute path to {@code <app>.exe}); embedded
     *        into {@code shell\open\command} as {@code "<command>" "%1"}
     * @return the full {@code .reg} file content (CRLF line endings, as Windows expects)
     * @throws IllegalArgumentException if {@code schemes} is empty or a scheme is malformed
     */
    public static String regScript(List<String> schemes, String command) {
        Objects.requireNonNull(schemes, "schemes");
        Objects.requireNonNull(command, "command");
        if (schemes.isEmpty()) {
            throw new IllegalArgumentException("schemes must not be empty");
        }
        // The command value is a quoted string inside a quoted .reg value: "<cmd>" "%1".
        String openCommand = escape("\"" + command + "\" \"%1\"");

        StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n");
        for (String scheme : schemes) {
            if (!SCHEME.matcher(scheme).matches()) {
                throw new IllegalArgumentException("Invalid URL scheme: " + scheme);
            }
            String base = "[HKEY_CURRENT_USER\\Software\\Classes\\" + scheme + "]";
            reg.append("\r\n").append(base).append("\r\n")
                    .append("@=\"URL:").append(scheme).append(" Protocol\"\r\n")
                    .append("\"URL Protocol\"=\"\"\r\n")
                    .append("\r\n")
                    .append("[HKEY_CURRENT_USER\\Software\\Classes\\").append(scheme)
                    .append("\\shell\\open\\command]\r\n")
                    .append("@=\"").append(openCommand).append("\"\r\n");
        }
        return reg.toString();
    }

    /** Escapes a string for a {@code .reg} double-quoted value: backslash and quote are doubled. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
