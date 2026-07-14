package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationApiTest {

    @Test
    void buildsAPlainThenEnrichedNotification() {
        InteractiveNotification plain = InteractiveNotification.of("Title", "Body");
        assertThat(plain.actions()).isEmpty();
        assertThat(plain.allowReply()).isFalse();
        assertThat(plain.replyPlaceholder()).isEmpty();

        InteractiveNotification rich = plain
                .withActions(new InteractiveNotification.Action("open", "Open"),
                        new InteractiveNotification.Action("dismiss", "Dismiss"))
                .withReply("Type a reply");
        assertThat(rich.actions()).extracting(InteractiveNotification.Action::id)
                .containsExactly("open", "dismiss");
        assertThat(rich.allowReply()).isTrue();
        assertThat(rich.replyPlaceholder()).isEqualTo("Type a reply");
    }

    @Test
    void rejectsBlankActionsAndTooManyActions() {
        assertThatThrownBy(() -> new InteractiveNotification.Action("", "x"))
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> new InteractiveNotification("t", "b",
                List.of(new InteractiveNotification.Action("a", "A"),
                        new InteractiveNotification.Action("b", "B"),
                        new InteractiveNotification.Action("c", "C"),
                        new InteractiveNotification.Action("d", "D"),
                        new InteractiveNotification.Action("e", "E")),
                false, ""))
                .isInstanceOf(JDeskException.class);
        assertThat(new InteractiveNotification("t", "b", List.of(), false, null).replyPlaceholder())
                .isEmpty();
    }

    @Test
    void responseFactoriesCarryActionAndReply() {
        assertThat(NotificationResponse.dismissed().actionId()).isEmpty();
        assertThat(NotificationResponse.dismissed().replyText()).isEmpty();
        assertThat(NotificationResponse.action("open").actionId()).contains("open");
        NotificationResponse reply = NotificationResponse.reply("comment", "hi there");
        assertThat(reply.actionId()).contains("comment");
        assertThat(reply.replyText()).contains("hi there");
        assertThat(new NotificationResponse(null, null).actionId()).isEqualTo(Optional.empty());
    }

    @Test
    void shareContentRequiresTextOrUrls() {
        assertThat(ShareContent.text("hello").text()).isEqualTo("hello");
        assertThat(ShareContent.urls("https://jdesk.dev").urls()).containsExactly("https://jdesk.dev");
        assertThatThrownBy(() -> new ShareContent("  ", List.of()))
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> new ShareContent("", List.of("ok", " ")))
                .isInstanceOf(JDeskException.class);
    }
}
