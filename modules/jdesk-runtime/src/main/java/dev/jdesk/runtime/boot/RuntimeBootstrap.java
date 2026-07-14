package dev.jdesk.runtime.boot;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskBootstrap;
import dev.jdesk.api.JDeskException;
import dev.jdesk.runtime.assets.CspValidator;
import dev.jdesk.webview.spi.PlatformProvider;
import dev.jdesk.instance.SingleInstance;
import dev.jdesk.instance.SingleInstanceException;
import dev.jdesk.instance.SingleInstanceResult;
import dev.jdesk.instance.SingleInstanceSession;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;

/**
 * {@link JDeskBootstrap} service implementation: selects exactly one platform provider
 * via JPMS {@link ServiceLoader} and runs the application. Zero or multiple providers
 * fail startup with a diagnostic (spec section 8).
 */
public final class RuntimeBootstrap implements JDeskBootstrap {
    /** Public no-arg constructor required by {@link ServiceLoader}. */
    public RuntimeBootstrap() {
    }

    @Override
    @SuppressWarnings("try")
    public int launch(ApplicationSpec spec, String[] args) {
        PlatformProvider provider = selectProvider(
                ServiceLoader.load(PlatformProvider.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList());
        try (ActivationDispatcher activations = spec.singleInstance()
                ? new ActivationDispatcher(spec.activationHandler()) : null) {
            SingleInstanceSession instanceSession = null;
            if (spec.singleInstance()) {
                try {
                    String configured = System.getProperty("jdesk.instance.dir");
                    Path stateDirectory = configured == null || configured.isBlank()
                            ? Path.of(System.getProperty("user.home"), ".jdesk", "instance")
                            : Path.of(configured);
                    SingleInstanceResult result = SingleInstance.acquire(spec.id(), stateDirectory,
                            Arrays.asList(args), activations::dispatch);
                    if (!result.primary()) {
                        return 0;
                    }
                    instanceSession = result.session().orElseThrow();
                } catch (SingleInstanceException e) {
                    throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                            "Single-instance coordination failed", e);
                }
            }
            try (SingleInstanceSession session = instanceSession;
                 JDeskRuntime runtime = new JDeskRuntime(spec, provider, optionsFor(spec),
                         activations == null ? spec.activationHandler() : activations::dispatch,
                         Arrays.asList(args))) {
                return runtime.run();
            }
        }
    }

    /**
     * Builds launch options, applying the application's CSP override when present.
     * Production launches screen the override through {@link CspValidator} (spec 12.4);
     * the {@code jdesk.security.acknowledgeUnsafeCsp} system property is the named opt-in.
     */
    static RuntimeOptions optionsFor(ApplicationSpec spec) {
        RuntimeOptions options = RuntimeOptions.fromSystemProperties();
        if (spec.contentSecurityPolicy().isEmpty()) {
            return options;
        }
        String csp = spec.contentSecurityPolicy().get();
        if (!options.devMode()) {
            CspValidator.validateForRelease(csp,
                    Boolean.getBoolean("jdesk.security.acknowledgeUnsafeCsp"));
        }
        return options.withSecurityHeader("Content-Security-Policy", csp);
    }

    /** Visible for tests: enforces the exactly-one-provider rule. */
    static PlatformProvider selectProvider(List<PlatformProvider> providers) {
        if (providers.size() != 1) {
            String found = providers.isEmpty()
                    ? "none"
                    : String.join(", ", providers.stream().map(PlatformProvider::id).toList());
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Expected exactly one PlatformProvider on the module path, found: " + found);
        }
        return providers.getFirst();
    }
}
