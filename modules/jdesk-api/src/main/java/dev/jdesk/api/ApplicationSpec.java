package dev.jdesk.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Everything {@link JDeskApplication.Builder} collected, handed to the runtime bootstrap. */
public record ApplicationSpec(
        String id,
        CommandRegistry commands,
        CapabilitySet capabilities,
        List<WindowConfig> windows,
        List<LifecycleListener> lifecycleListeners,
        Optional<String> devServerUrl) {

    private static final Pattern APP_ID =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z][a-zA-Z0-9-]*)+");

    public ApplicationSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(capabilities, "capabilities");
        windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
        lifecycleListeners = List.copyOf(Objects.requireNonNull(lifecycleListeners, "lifecycleListeners"));
        Objects.requireNonNull(devServerUrl, "devServerUrl");
        if (!APP_ID.matcher(id).matches()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Application id must be reverse-DNS style, e.g. dev.jdesk.example");
        }
        if (windows.isEmpty()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "At least one window is required");
        }
        long distinctIds = windows.stream().map(w -> w.id().value()).distinct().count();
        if (distinctIds != windows.size()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Duplicate window ids");
        }
    }
}
