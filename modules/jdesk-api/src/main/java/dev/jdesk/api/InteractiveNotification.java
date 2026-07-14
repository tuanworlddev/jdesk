package dev.jdesk.api;

import java.util.List;
import java.util.Objects;

/**
 * A user notification that can carry action buttons and an inline reply field. The user's choice
 * comes back as a {@link NotificationResponse} through the {@link CompletionStage} returned by
 * {@link ApplicationHandle#showNotification(InteractiveNotification)} — completing only when the
 * user acts on (or dismisses) the notification, not when it is delivered.
 *
 * <p>Platforms cap the number of buttons (macOS shows the first action as the main button plus a
 * small "additional actions" menu; Windows toasts allow up to five). Keep {@code actions} short.
 */
public record InteractiveNotification(
        String title,
        String body,
        List<Action> actions,
        boolean allowReply,
        String replyPlaceholder) {

    /** A single labelled action button; {@code id} identifies it in the response. */
    public record Action(String id, String title) {
        public Action {
            id = Objects.requireNonNull(id, "id");
            title = Objects.requireNonNull(title, "title");
            if (id.isBlank() || title.isBlank()) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "Notification action id and title must be non-blank");
            }
        }
    }

    public InteractiveNotification {
        title = Objects.requireNonNull(title, "title");
        body = Objects.requireNonNull(body, "body");
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        if (actions.size() > 4) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "A notification supports at most four actions");
        }
        replyPlaceholder = replyPlaceholder == null ? "" : replyPlaceholder;
    }

    /** A plain notification with a title and body, no actions or reply. */
    public static InteractiveNotification of(String title, String body) {
        return new InteractiveNotification(title, body, List.of(), false, "");
    }

    /** Adds action buttons to this notification. */
    public InteractiveNotification withActions(Action... actions) {
        return new InteractiveNotification(title, body, List.of(actions), allowReply,
                replyPlaceholder);
    }

    /** Enables the inline reply field with the given placeholder. */
    public InteractiveNotification withReply(String placeholder) {
        return new InteractiveNotification(title, body, actions, true, placeholder);
    }
}
