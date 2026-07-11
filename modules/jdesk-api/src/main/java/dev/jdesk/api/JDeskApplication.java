package dev.jdesk.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Application entry point:
 *
 * <pre>{@code
 * JDeskApplication.builder()
 *     .id("dev.jdesk.example")
 *     .commands(GeneratedCommands.create(services))
 *     .capabilities(Capabilities.fromResource("jdesk-capabilities.json"))
 *     .window(WindowConfig.builder().id("main").title("Example")
 *         .size(1100, 720).entry("jdesk://app/index.html").build())
 *     .run(args);
 * }</pre>
 */
public final class JDeskApplication {
    private JDeskApplication() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private CommandRegistry commands = CommandRegistry.of();
        private CapabilitySet capabilities = CapabilitySet.empty();
        private final List<WindowConfig> windows = new ArrayList<>();
        private final List<LifecycleListener> listeners = new ArrayList<>();
        private Optional<String> devServerUrl = Optional.empty();

        private Builder() {
        }

        public Builder id(String id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        public Builder commands(CommandRegistry commands) {
            this.commands = Objects.requireNonNull(commands, "commands");
            return this;
        }

        public Builder capabilities(CapabilitySet capabilities) {
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            return this;
        }

        public Builder window(WindowConfig window) {
            windows.add(Objects.requireNonNull(window, "window"));
            return this;
        }

        public Builder lifecycle(LifecycleListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /** Development-only exact origin, e.g. {@code http://127.0.0.1:5173}. */
        public Builder devServerUrl(String url) {
            this.devServerUrl = Optional.of(url);
            return this;
        }

        public ApplicationSpec buildSpec() {
            if (id == null) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST, "Application id is required");
            }
            return new ApplicationSpec(id, commands, capabilities, windows, listeners, devServerUrl);
        }

        /** Builds the spec, locates the runtime bootstrap, and runs until shutdown. */
        public int run(String[] args) {
            ApplicationSpec spec = buildSpec();
            ServiceLoader<JDeskBootstrap> loader =
                    ServiceLoader.load(JDeskBootstrap.class, JDeskApplication.class.getClassLoader());
            List<JDeskBootstrap> found = loader.stream().map(ServiceLoader.Provider::get).toList();
            if (found.size() != 1) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Expected exactly one JDeskBootstrap provider, found " + found.size()
                                + ". Is dev.jdesk.runtime on the module path?");
            }
            return found.getFirst().launch(spec, args == null ? new String[0] : args.clone());
        }
    }
}
