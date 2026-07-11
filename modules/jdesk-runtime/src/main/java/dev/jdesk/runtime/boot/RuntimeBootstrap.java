package dev.jdesk.runtime.boot;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskBootstrap;
import dev.jdesk.api.JDeskException;
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
    public int launch(ApplicationSpec spec, String[] args) {
        PlatformProvider provider = selectProvider(
                ServiceLoader.load(PlatformProvider.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList());
        SingleInstanceSession instanceSession = null;
        if (spec.singleInstance()) {
            try {
                String configured = System.getProperty("jdesk.instance.dir");
                Path stateDirectory = configured == null || configured.isBlank()
                        ? Path.of(System.getProperty("user.home"), ".jdesk", "instance")
                        : Path.of(configured);
                SingleInstanceResult result = SingleInstance.acquire(spec.id(), stateDirectory,
                        Arrays.asList(args), spec.activationHandler());
                if (!result.primary()) return 0;
                instanceSession = result.session().orElseThrow();
            } catch (SingleInstanceException e) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Single-instance coordination failed", e);
            }
        }
        try (SingleInstanceSession session = instanceSession;
             JDeskRuntime runtime = new JDeskRuntime(spec, provider,
                     RuntimeOptions.fromSystemProperties())) {
            return runtime.run();
        }
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
