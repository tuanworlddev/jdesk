package dev.jdesk.platform.macos;

/** Pure application-name derivation kept independent from AppKit/Objective-C initialization. */
final class MacApplicationName {
    private MacApplicationName() {}

    /**
     * Prefers the packaged application-name override, then derives a readable name from the last
     * segment of the reverse-DNS application id.
     */
    static String displayName(String applicationId) {
        String override = System.getProperty("jdesk.applicationName");
        if (override != null && !override.isBlank()) {
            return override;
        }
        String segment = applicationId.substring(applicationId.lastIndexOf('.') + 1);
        if (segment.isEmpty()) {
            return null;
        }
        return Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
    }
}
