package dev.jdesk.runtime.boot;

import dev.jdesk.api.ApplicationSpec;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskBootstrap;
import dev.jdesk.api.JDeskException;
import dev.jdesk.webview.spi.PlatformProvider;
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
        try (JDeskRuntime runtime = new JDeskRuntime(spec, provider, RuntimeOptions.fromSystemProperties())) {
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
