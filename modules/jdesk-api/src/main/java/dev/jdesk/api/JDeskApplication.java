package dev.jdesk.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Application entry point:
 *
 * <pre>{@code
 * JDeskApplication.builder()
 *     .id("dev.jdesk.example")
 *     .commands(GeneratedCommands.create(services))
 *     .capabilities(Capabilities.fromResource(
 *         App.class.getModule(), "jdesk-capabilities.json"))
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
        private CommandRegistry frontendEvents = CommandRegistry.of();
        private CapabilitySet capabilities = CapabilitySet.empty();
        private final List<WindowConfig> windows = new ArrayList<>();
        private final List<LifecycleListener> listeners = new ArrayList<>();
        private Optional<String> devServerUrl = Optional.empty();
        private boolean singleInstance;
        private Consumer<List<String>> activationHandler = ignored -> { };
        private Optional<String> contentSecurityPolicy = Optional.empty();
        private final java.util.LinkedHashMap<String, AssetRoute> assetRoutes =
                new java.util.LinkedHashMap<>();
        private static final java.util.regex.Pattern ROUTE_PREFIX =
                java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)*");

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

        /** Event definitions accepted from JavaScript; handlers receive InvocationContext. */
        public Builder frontendEvents(CommandRegistry events) {
            this.frontendEvents = Objects.requireNonNull(events, "events"); return this;
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

        /**
         * Replaces the default Content-Security-Policy applied to every {@code jdesk://app/}
         * response. The default is strict ({@code default-src 'self'} — no remote content);
         * override it when the app legitimately needs external resources, e.g. streaming
         * media from a CDN:
         *
         * <pre>{@code
         * .contentSecurityPolicy("default-src 'self'; media-src 'self' https:; "
         *         + "img-src 'self' data: https:; connect-src 'self' https://api.example.com")
         * }</pre>
         *
         * Production launches screen the policy: {@code 'unsafe-inline'}/{@code 'unsafe-eval'}/
         * {@code 'unsafe-hashes'} are rejected at startup unless the
         * {@code jdesk.security.acknowledgeUnsafeCsp} system property is set.
         */
        public Builder contentSecurityPolicy(String csp) {
            Objects.requireNonNull(csp, "csp");
            if (csp.isBlank()) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "contentSecurityPolicy must not be blank; omit the call to keep the default policy");
            }
            this.contentSecurityPolicy = Optional.of(csp);
            return this;
        }

        /**
         * Registers a Java-served asset route under {@code jdesk://app/<prefix>/...}.
         * Requests matching the prefix are answered by {@code route} through the
         * streaming asset pipeline (Range/206 included) — the efficient path for binary
         * content like proxied or cached images. See {@link AssetRoute}.
         */
        public Builder assetRoute(String prefix, AssetRoute route) {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(route, "route");
            if (!ROUTE_PREFIX.matcher(prefix).matches()) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "Asset route prefix must match [a-z0-9-] segments, e.g. \"proxy/images\": "
                                + prefix);
            }
            if (assetRoutes.putIfAbsent(prefix, route) != null) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "Duplicate asset route prefix: " + prefix);
            }
            return this;
        }

        /**
         * Enforces one running process for this application id. Later launches deliver their
         * command-line arguments (including deep-link URIs) to {@code activationHandler}.
         */
        public Builder singleInstance(Consumer<List<String>> activationHandler) {
            this.singleInstance = true;
            this.activationHandler = Objects.requireNonNull(activationHandler, "activationHandler");
            return this;
        }

        public ApplicationSpec buildSpec() {
            if (id == null) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST, "Application id is required");
            }
            return new ApplicationSpec(id, commands, capabilities, windows, listeners,
                    devServerUrl, frontendEvents, singleInstance, activationHandler,
                    contentSecurityPolicy, assetRoutes);
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
