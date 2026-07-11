package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Window ids: 1..64 chars of [a-zA-Z0-9._-]. */
class WindowIdTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "main",
            "a",
            "MAIN",
            "settings-window",
            "window_2",
            "dev.jdesk.main",
            "A1b2C3",
            "....",
            "-_-",
    })
    void acceptsValidIds(String id) {
        WindowId windowId = new WindowId(id);
        assertThat(windowId.value()).isEqualTo(id);
        assertThat(windowId.toString()).isEqualTo(id);
    }

    @Test
    void acceptsMaximumLengthOf64() {
        String id = "a".repeat(64);
        assertThat(new WindowId(id).value()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "has space",
            " leading",
            "trailing ",
            "path/like",
            "back\\slash",
            "fenêtre",       // unicode letter outside the grammar
            "窗口",       // CJK
            "emoji😀",
            "semi;colon",
            "colon:name",
            "tab\tname",
    })
    void rejectsInvalidIds(String id) {
        JDeskException e = catchThrowableOfType(JDeskException.class, () -> new WindowId(id));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsIdsLongerThan64() {
        String tooLong = "a".repeat(65);
        JDeskException e = catchThrowableOfType(JDeskException.class, () -> new WindowId(tooLong));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsNull() {
        JDeskException e = catchThrowableOfType(JDeskException.class, () -> new WindowId(null));
        assertThat(e).isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
