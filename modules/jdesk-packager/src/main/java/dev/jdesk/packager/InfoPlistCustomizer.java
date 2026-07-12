package dev.jdesk.packager;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Post-processes a jpackage-generated macOS {@code Info.plist} to add things jpackage cannot:
 * {@code CFBundleURLTypes} (so the OS routes {@code scheme://} deep links to the app) and
 * arbitrary usage-description keys. Pure text transformation — idempotent (re-running is a
 * no-op) and XML-escaping every injected value.
 */
public final class InfoPlistCustomizer {

    private InfoPlistCustomizer() {
    }

    /**
     * @param plist the existing Info.plist XML
     * @param urlName value for {@code CFBundleURLName} (e.g. the bundle id)
     * @param urlSchemes schemes to register (e.g. {@code ["jdesk-forge"]}); empty adds no
     *        {@code CFBundleURLTypes}
     * @param usageDescriptions extra plist keys → string values (e.g. usage descriptions)
     * @return the customized plist; unchanged if {@code CFBundleURLTypes} is already present
     *         and no new usage-description keys are missing
     * @throws IllegalArgumentException if the plist has no root {@code </dict>}
     */
    public static String customize(String plist, String urlName, List<String> urlSchemes,
            Map<String, String> usageDescriptions) {
        Objects.requireNonNull(plist, "plist");
        Objects.requireNonNull(urlName, "urlName");
        Objects.requireNonNull(urlSchemes, "urlSchemes");
        Objects.requireNonNull(usageDescriptions, "usageDescriptions");

        int rootClose = plist.lastIndexOf("</dict>");
        if (rootClose < 0) {
            throw new IllegalArgumentException("Info.plist has no root </dict>");
        }

        StringBuilder injection = new StringBuilder();
        if (!urlSchemes.isEmpty() && !plist.contains("<key>CFBundleURLTypes</key>")) {
            injection.append("  <key>CFBundleURLTypes</key>\n")
                    .append("  <array>\n")
                    .append("    <dict>\n")
                    .append("      <key>CFBundleURLName</key>\n")
                    .append("      <string>").append(escape(urlName)).append("</string>\n")
                    .append("      <key>CFBundleURLSchemes</key>\n")
                    .append("      <array>\n");
            for (String scheme : urlSchemes) {
                injection.append("        <string>").append(escape(scheme)).append("</string>\n");
            }
            injection.append("      </array>\n")
                    .append("    </dict>\n")
                    .append("  </array>\n");
        }
        for (Map.Entry<String, String> entry : usageDescriptions.entrySet()) {
            String key = "<key>" + escape(entry.getKey()) + "</key>";
            if (plist.contains(key)) {
                continue; // idempotent: already present
            }
            injection.append("  ").append(key).append("\n")
                    .append("  <string>").append(escape(entry.getValue())).append("</string>\n");
        }
        if (injection.isEmpty()) {
            return plist;
        }
        return plist.substring(0, rootClose) + injection + plist.substring(rootClose);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
