package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Window configuration builder and validation. */
class WindowConfigTest {

    @Test
    void builderHappyPath() {
        WindowConfig config = WindowConfig.builder()
                .id("main")
                .title("Example")
                .size(1100, 720)
                .resizable(false)
                .entry("jdesk://app/index.html")
                .build();

        assertThat(config.id()).isEqualTo(new WindowId("main"));
        assertThat(config.title()).isEqualTo("Example");
        assertThat(config.width()).isEqualTo(1100);
        assertThat(config.height()).isEqualTo(720);
        assertThat(config.resizable()).isFalse();
        assertThat(config.entry()).isEqualTo(URI.create("jdesk://app/index.html"));
    }

    @Test
    void builderDefaultsAre800By600ResizableEmptyTitle() {
        WindowConfig config = WindowConfig.builder()
                .id("main")
                .entry("jdesk://app/index.html")
                .build();

        assertThat(config.width()).isEqualTo(800);
        assertThat(config.height()).isEqualTo(600);
        assertThat(config.resizable()).isTrue();
        assertThat(config.title()).isEmpty();
        assertThat(config.minWidth()).isZero();
        assertThat(config.minHeight()).isZero();
        assertThat(config.startMaximized()).isFalse();
        assertThat(config.rememberBounds()).isFalse();
    }

    @Test
    void positionFlowsThroughBuilder() {
        WindowConfig config = WindowConfig.builder()
                .id("main").entry("jdesk://app/index.html")
                .position(120, 80).build();
        assertThat(config.position()).contains(new WindowConfig.Position(120, 80));
        // Default is unset (OS places the window).
        assertThat(WindowConfig.builder().id("m").entry("jdesk://app/i.html").build().position())
                .isEmpty();
    }

    @Test
    void minSizeMaximizedAndRememberBoundsFlowThroughBuilder() {
        WindowConfig config = WindowConfig.builder()
                .id("main")
                .entry("jdesk://app/index.html")
                .size(1200, 800)
                .minSize(600, 400)
                .startMaximized(true)
                .rememberBounds(true)
                .build();

        assertThat(config.minWidth()).isEqualTo(600);
        assertThat(config.minHeight()).isEqualTo(400);
        assertThat(config.startMaximized()).isTrue();
        assertThat(config.rememberBounds()).isTrue();
    }

    @Test
    void minSizeLargerThanInitialSizeIsRejected() {
        WindowConfig.Builder builder = WindowConfig.builder()
                .id("main")
                .entry("jdesk://app/index.html")
                .size(800, 600)
                .minSize(900, 100);
        org.assertj.core.api.Assertions.assertThatThrownBy(builder::build)
                .isInstanceOf(JDeskException.class)
                .hasMessageContaining("minimum size");
    }

    @Test
    void missingIdThrowsInvalidRequest() {
        WindowConfig.Builder builder = WindowConfig.builder().entry("jdesk://app/index.html");
        JDeskException e = catchThrowableOfType(JDeskException.class, builder::build);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(e.publicMessage()).contains("id");
    }

    @Test
    void missingEntryThrowsInvalidRequest() {
        WindowConfig.Builder builder = WindowConfig.builder().id("main");
        JDeskException e = catchThrowableOfType(JDeskException.class, builder::build);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(e.publicMessage()).contains("entry");
    }

    @ParameterizedTest
    @CsvSource({
            "0, 600",
            "800, 0",
            "-1, 600",
            "800, -50",
            "32768, 600",
            "800, 32768",
            "0, 0",
            "-100, -100",
    })
    void outOfRangeSizesThrowInvalidRequest(int width, int height) {
        WindowConfig.Builder builder = WindowConfig.builder()
                .id("main")
                .size(width, height)
                .entry("jdesk://app/index.html");

        JDeskException e = catchThrowableOfType(JDeskException.class, builder::build);
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1",
            "32767, 32767",
            "1, 32767",
    })
    void boundarySizesAreAccepted(int width, int height) {
        WindowConfig config = WindowConfig.builder()
                .id("main")
                .size(width, height)
                .entry("jdesk://app/index.html")
                .build();
        assertThat(config.width()).isEqualTo(width);
        assertThat(config.height()).isEqualTo(height);
    }

    @Test
    void canonicalConstructorValidatesToo() {
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> new WindowConfig(new WindowId("main"), "t", 0, 600, true,
                        URI.create("jdesk://app/index.html")));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void builderRejectsInvalidWindowIdEagerly() {
        WindowConfig.Builder builder = WindowConfig.builder();
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> builder.id("not valid!"));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
