package dev.jdesk.packager;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds a freedesktop {@code .desktop} entry that registers {@code scheme://} deep links on
 * Linux, the counterpart to {@link InfoPlistCustomizer} on macOS. The {@code MimeType} line lists
 * one {@code x-scheme-handler/<scheme>} per scheme, and {@code Exec} ends with the {@code %u}
 * field code so the desktop environment passes the activated URL as an argument (which JDesk's
 * single-instance layer then forwards to the app's activation handler).
 *
 * <p>Pure text generation — no filesystem or OS calls, so it is unit-testable on any host. The
 * generated file becomes effective once it is installed into an applications directory (a
 * {@code .deb}/{@code .rpm} postinst, or copying it to {@code ~/.local/share/applications/}) and
 * {@code update-desktop-database} has run; that install step is the Linux analogue of macOS
 * Launch Services reading {@code Info.plist}.
 */
public final class LinuxDesktopEntry {

    /** Freedesktop scheme grammar (RFC 3986 scheme, lower-cased): letter then letter/digit/+.-. */
    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*");

    private LinuxDesktopEntry() {
    }

    /**
     * @param appName human-readable {@code Name=} (a launcher/menu label)
     * @param exec the launcher command (e.g. the absolute path to {@code bin/<app>}); quoted if it
     *        contains spaces. {@code %u} is appended for the URL argument
     * @param schemes {@code scheme://} names (without {@code ://}); must be non-empty and valid
     * @return the full {@code .desktop} file content
     * @throws IllegalArgumentException if {@code schemes} is empty or a scheme is malformed
     */
    public static String build(String appName, String exec, List<String> schemes) {
        Objects.requireNonNull(appName, "appName");
        Objects.requireNonNull(exec, "exec");
        Objects.requireNonNull(schemes, "schemes");
        if (schemes.isEmpty()) {
            throw new IllegalArgumentException("schemes must not be empty");
        }
        StringBuilder mime = new StringBuilder();
        for (String scheme : schemes) {
            if (!SCHEME.matcher(scheme).matches()) {
                throw new IllegalArgumentException("Invalid URL scheme: " + scheme);
            }
            mime.append("x-scheme-handler/").append(scheme).append(';');
        }
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=" + sanitizeValue(appName) + "\n"
                + "Exec=" + execCommand(exec) + " %u\n"
                + "Terminal=false\n"
                + "NoDisplay=false\n"
                + "MimeType=" + mime + "\n";
    }

    /** Quotes the program token if it contains spaces; strips value-breaking control chars. */
    private static String execCommand(String exec) {
        String clean = sanitizeValue(exec);
        return clean.contains(" ") ? "\"" + clean + "\"" : clean;
    }

    /** Desktop-entry values are single-line; drop newlines/CRs that would corrupt the file. */
    private static String sanitizeValue(String value) {
        return value.replace("\r", "").replace("\n", " ");
    }
}
