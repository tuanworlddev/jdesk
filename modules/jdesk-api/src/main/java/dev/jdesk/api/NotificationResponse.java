package dev.jdesk.api;

import java.util.Optional;

/**
 * How the user answered an {@link InteractiveNotification}: which action button they pressed (if
 * any) and the text they typed into the inline reply field (if any). A plain body click or a
 * dismissal yields empty values.
 */
public record NotificationResponse(Optional<String> actionId, Optional<String> replyText) {

    public NotificationResponse {
        actionId = actionId == null ? Optional.empty() : actionId;
        replyText = replyText == null ? Optional.empty() : replyText;
    }

    /** The user dismissed or clicked the body — no specific action, no reply. */
    public static NotificationResponse dismissed() {
        return new NotificationResponse(Optional.empty(), Optional.empty());
    }

    /** The user pressed a specific action button. */
    public static NotificationResponse action(String actionId) {
        return new NotificationResponse(Optional.of(actionId), Optional.empty());
    }

    /** The user typed an inline reply (optionally under a specific action). */
    public static NotificationResponse reply(String actionId, String text) {
        return new NotificationResponse(Optional.ofNullable(actionId), Optional.of(text));
    }
}
