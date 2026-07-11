package dev.jdesk.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** Everything {@link JDeskApplication.Builder} collected, handed to the runtime bootstrap. */
public record ApplicationSpec(
        String id,
        CommandRegistry commands,
        CapabilitySet capabilities,
        List<WindowConfig> windows,
        List<LifecycleListener> lifecycleListeners,
        Optional<String> devServerUrl,
        CommandRegistry frontendEvents,
        boolean singleInstance,
        Consumer<List<String>> activationHandler,
        Optional<String> contentSecurityPolicy,
        Map<String, AssetRoute> assetRoutes) {

    private static final Pattern APP_ID =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z][a-zA-Z0-9-]*)+");

    public ApplicationSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(capabilities, "capabilities");
        windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
        lifecycleListeners = List.copyOf(Objects.requireNonNull(lifecycleListeners, "lifecycleListeners"));
        Objects.requireNonNull(devServerUrl, "devServerUrl");
        Objects.requireNonNull(frontendEvents, "frontendEvents");
        Objects.requireNonNull(activationHandler, "activationHandler");
        Objects.requireNonNull(contentSecurityPolicy, "contentSecurityPolicy");
        if (contentSecurityPolicy.isPresent() && contentSecurityPolicy.get().isBlank()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "contentSecurityPolicy must not be blank; omit it to keep the default policy");
        }
        assetRoutes = Map.copyOf(Objects.requireNonNull(assetRoutes, "assetRoutes"));
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

    public ApplicationSpec(String id, CommandRegistry commands, CapabilitySet capabilities,
            List<WindowConfig> windows, List<LifecycleListener> lifecycleListeners,
            Optional<String> devServerUrl) {
        this(id, commands, capabilities, windows, lifecycleListeners, devServerUrl,
                CommandRegistry.of(), false, ignored -> { }, Optional.empty(), Map.of());
    }

    public ApplicationSpec(String id, CommandRegistry commands, CapabilitySet capabilities,
            List<WindowConfig> windows, List<LifecycleListener> lifecycleListeners,
            Optional<String> devServerUrl, CommandRegistry frontendEvents) {
        this(id, commands, capabilities, windows, lifecycleListeners, devServerUrl,
                frontendEvents, false, ignored -> { }, Optional.empty(), Map.of());
    }

    public ApplicationSpec(String id, CommandRegistry commands, CapabilitySet capabilities,
            List<WindowConfig> windows, List<LifecycleListener> lifecycleListeners,
            Optional<String> devServerUrl, CommandRegistry frontendEvents,
            boolean singleInstance, Consumer<List<String>> activationHandler) {
        this(id, commands, capabilities, windows, lifecycleListeners, devServerUrl,
                frontendEvents, singleInstance, activationHandler, Optional.empty(), Map.of());
    }

    public ApplicationSpec(String id, CommandRegistry commands, CapabilitySet capabilities,
            List<WindowConfig> windows, List<LifecycleListener> lifecycleListeners,
            Optional<String> devServerUrl, CommandRegistry frontendEvents,
            boolean singleInstance, Consumer<List<String>> activationHandler,
            Optional<String> contentSecurityPolicy) {
        this(id, commands, capabilities, windows, lifecycleListeners, devServerUrl,
                frontendEvents, singleInstance, activationHandler, contentSecurityPolicy,
                Map.of());
    }
}
