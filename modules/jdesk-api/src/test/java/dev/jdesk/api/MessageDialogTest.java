package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MessageDialog record validation and the {@link MessageDialog#ok} factory. */
class MessageDialogTest {

    @Test
    void okFactoryProducesSingleButtonInfoDialog() {
        MessageDialog dialog = MessageDialog.ok("Title", "Body");
        assertThat(dialog.title()).isEqualTo("Title");
        assertThat(dialog.message()).isEqualTo("Body");
        assertThat(dialog.kind()).isEqualTo(MessageDialog.Kind.INFO);
        assertThat(dialog.buttons()).containsExactly("OK");
    }

    @Test
    void acceptsUpToThreeButtons() {
        MessageDialog dialog = new MessageDialog("t", "m", MessageDialog.Kind.WARNING,
                List.of("Yes", "No", "Cancel"));
        assertThat(dialog.buttons()).containsExactly("Yes", "No", "Cancel");
        assertThat(dialog.kind()).isEqualTo(MessageDialog.Kind.WARNING);
    }

    @Test
    void buttonsAreDefensivelyCopiedAndImmutable() {
        List<String> buttons = new ArrayList<>(List.of("OK"));
        MessageDialog dialog = new MessageDialog("t", "m", MessageDialog.Kind.ERROR, buttons);
        buttons.add("Extra");
        assertThat(dialog.buttons()).containsExactly("OK");
        assertThatThrownBy(() -> dialog.buttons().add("z"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyButtonsRejected() {
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> new MessageDialog("t", "m", MessageDialog.Kind.INFO, List.of()));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void tooManyButtonsRejected() {
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> new MessageDialog("t", "m", MessageDialog.Kind.INFO,
                        List.of("a", "b", "c", "d")));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void blankButtonLabelRejected() {
        JDeskException e = catchThrowableOfType(JDeskException.class,
                () -> new MessageDialog("t", "m", MessageDialog.Kind.INFO, List.of("OK", "  ")));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void nullComponentsRejected() {
        assertThatThrownBy(() -> new MessageDialog(null, "m", MessageDialog.Kind.INFO, List.of("OK")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageDialog("t", null, MessageDialog.Kind.INFO, List.of("OK")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageDialog("t", "m", null, List.of("OK")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MessageDialog("t", "m", MessageDialog.Kind.INFO, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void kindEnumExposesAllValues() {
        assertThat(MessageDialog.Kind.values())
                .containsExactly(MessageDialog.Kind.INFO, MessageDialog.Kind.WARNING,
                        MessageDialog.Kind.ERROR);
        assertThat(MessageDialog.Kind.valueOf("ERROR")).isEqualTo(MessageDialog.Kind.ERROR);
    }
}
