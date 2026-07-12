package dev.jdesk.runtime.diagnostics;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Explicit inputs and hard limits for a redacted support bundle. */
public record SupportBundleOptions(
        String applicationId,
        String applicationVersion,
        List<Path> logFiles,
        long maxBytesPerLog,
        long maxTotalLogBytes) {
    public SupportBundleOptions {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId must not be blank");
        }
        if (applicationVersion == null || applicationVersion.isBlank()) {
            throw new IllegalArgumentException("applicationVersion must not be blank");
        }
        logFiles = List.copyOf(Objects.requireNonNull(logFiles, "logFiles"));
        if (maxBytesPerLog < 1 || maxTotalLogBytes < 1
                || maxBytesPerLog > maxTotalLogBytes) {
            throw new IllegalArgumentException("Invalid support bundle size limits");
        }
    }

    public static SupportBundleOptions defaults(String applicationId,
            String applicationVersion, List<Path> logFiles) {
        return new SupportBundleOptions(applicationId, applicationVersion, logFiles,
                2L * 1024 * 1024, 8L * 1024 * 1024);
    }
}
