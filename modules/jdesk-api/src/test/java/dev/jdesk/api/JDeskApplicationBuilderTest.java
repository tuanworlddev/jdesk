package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** JDeskApplication.Builder: spec assembly, validation, and bootstrap discovery. */
class JDeskApplicationBuilderTest {

    private static WindowConfig window(String id) {
        return WindowConfig.builder().id(id).entry("jdesk://app/index.html").build();
    }

    @Test
    void buildSpecCollectsEverything() {
        LifecycleListener listener = new LifecycleListener() { };
        WindowConfig main = window("main");

        ApplicationSpec spec = JDeskApplication.builder()
                .id("dev.jdesk.example")
                .commands(CommandRegistry.of())
                .capabilities(CapabilitySet.empty())
                .window(main)
                .lifecycle(listener)
                .devServerUrl("http://127.0.0.1:5173")
                .buildSpec();

        assertThat(spec.id()).isEqualTo("dev.jdesk.example");
        assertThat(spec.windows()).containsExactly(main);
        assertThat(spec.lifecycleListeners()).containsExactly(listener);
        assertThat(spec.devServerUrl()).isEqualTo(Optional.of("http://127.0.0.1:5173"));
    }

    @Test
    void buildSpecWithoutIdThrowsInvalidRequest() {
        JDeskApplication.Builder builder = JDeskApplication.builder().window(window("main"));
        JDeskException e = catchThrowableOfType(JDeskException.class, builder::buildSpec);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(e.publicMessage()).contains("id");
    }

    @Test
    void buildSpecWithoutWindowsThrowsInvalidRequest() {
        JDeskApplication.Builder builder = JDeskApplication.builder().id("dev.jdesk.example");
        JDeskException e = catchThrowableOfType(JDeskException.class, builder::buildSpec);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void buildSpecWithBadIdThrowsInvalidRequest() {
        JDeskApplication.Builder builder = JDeskApplication.builder()
                .id("noDots")
                .window(window("main"));
        JDeskException e = catchThrowableOfType(JDeskException.class, builder::buildSpec);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void runWithoutBootstrapProviderThrowsIllegalStateWithProviderCount() {
        // No dev.jdesk.runtime on the test classpath, so ServiceLoader finds 0 providers.
        JDeskApplication.Builder builder = JDeskApplication.builder()
                .id("dev.jdesk.example")
                .window(window("main"));

        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> builder.run(new String[0]));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.ILLEGAL_STATE);
        assertThat(e.publicMessage()).contains("found 0");
        assertThat(e.publicMessage()).contains("JDeskBootstrap");
    }

    @Test
    void runValidatesSpecBeforeLookingForBootstrap() {
        JDeskApplication.Builder builder = JDeskApplication.builder(); // no id, no window
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> builder.run(new String[0]));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
