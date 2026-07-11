package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ApplicationSpec validation: app id grammar, windows, defensive copies. */
class ApplicationSpecTest {

    private static WindowConfig window(String id) {
        return WindowConfig.builder().id(id).entry("jdesk://app/index.html").build();
    }

    private static ApplicationSpec spec(String appId, List<WindowConfig> windows,
            List<LifecycleListener> listeners) {
        return new ApplicationSpec(appId, CommandRegistry.of(), CapabilitySet.empty(),
                windows, listeners, Optional.empty());
    }

    @Test
    void validSpecIsAccepted() {
        ApplicationSpec spec = spec("dev.jdesk.example",
                List.of(window("main"), window("settings")), List.of());

        assertThat(spec.id()).isEqualTo("dev.jdesk.example");
        assertThat(spec.windows()).hasSize(2);
        assertThat(spec.lifecycleListeners()).isEmpty();
        assertThat(spec.devServerUrl()).isEmpty();
        assertThat(spec.commands().size()).isZero();
        assertThat(spec.capabilities().grants()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.example.app",
            "dev.jdesk.example",
            "Org.Example.MyApp",
            "a.b",
            "io.jdesk.my-app",   // hyphen allowed in non-first segments
    })
    void acceptsReverseDnsStyleIds(String appId) {
        assertThat(spec(appId, List.of(window("main")), List.of()).id()).isEqualTo(appId);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "noDots",
            "1abc",
            "1abc.def",
            "",
            ".leading",
            "trailing.",
            "double..dots",
            "has space.app",
            "-dash.first",
    })
    void rejectsBadAppIds(String appId) {
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> spec(appId, List.of(window("main")), List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void zeroWindowsRejected() {
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> spec("dev.jdesk.example", List.of(), List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(e.publicMessage()).contains("window");
    }

    @Test
    void duplicateWindowIdsRejected() {
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> spec("dev.jdesk.example",
                        List.of(window("main"), window("other"), window("main")), List.of()));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(e.publicMessage()).contains("Duplicate");
    }

    @Test
    void windowListIsDefensivelyCopiedAndImmutable() {
        List<WindowConfig> windows = new ArrayList<>();
        windows.add(window("main"));
        ApplicationSpec spec = spec("dev.jdesk.example", windows, List.of());

        windows.add(window("sneaky"));
        assertThat(spec.windows()).hasSize(1);

        assertThatThrownBy(() -> spec.windows().add(window("other")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lifecycleListenerListIsDefensivelyCopiedAndImmutable() {
        LifecycleListener listener = new LifecycleListener() { };
        List<LifecycleListener> listeners = new ArrayList<>();
        listeners.add(listener);
        ApplicationSpec spec = spec("dev.jdesk.example", List.of(window("main")), listeners);

        listeners.clear();
        assertThat(spec.lifecycleListeners()).containsExactly(listener);

        assertThatThrownBy(() -> spec.lifecycleListeners().add(new LifecycleListener() { }))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullComponentsRejected() {
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new ApplicationSpec(null, CommandRegistry.of(), CapabilitySet.empty(),
                        List.of(window("main")), List.of(), Optional.empty())))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new ApplicationSpec("dev.jdesk.example", null, CapabilitySet.empty(),
                        List.of(window("main")), List.of(), Optional.empty())))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new ApplicationSpec("dev.jdesk.example", CommandRegistry.of(), null,
                        List.of(window("main")), List.of(), Optional.empty())))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new ApplicationSpec("dev.jdesk.example", CommandRegistry.of(),
                        CapabilitySet.empty(), null, List.of(), Optional.empty())))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new ApplicationSpec("dev.jdesk.example", CommandRegistry.of(),
                        CapabilitySet.empty(), List.of(window("main")), null, Optional.empty())))
                .isNotNull();
        assertThat(catchThrowableOfType(NullPointerException.class,
                () -> new ApplicationSpec("dev.jdesk.example", CommandRegistry.of(),
                        CapabilitySet.empty(), List.of(window("main")), List.of(), null)))
                .isNotNull();
    }
}
