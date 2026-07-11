package dev.jdesk.api;

import java.util.List;
import java.util.Objects;

/** Immutable request for an OS-native modal message dialog. */
public record MessageDialog(String title, String message, Kind kind, List<String> buttons) {
    public enum Kind { INFO, WARNING, ERROR }

    public MessageDialog {
        title = Objects.requireNonNull(title, "title");
        message = Objects.requireNonNull(message, "message");
        Objects.requireNonNull(kind, "kind");
        buttons = List.copyOf(Objects.requireNonNull(buttons, "buttons"));
        if (buttons.isEmpty() || buttons.size() > 3 || buttons.stream().anyMatch(String::isBlank)) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Message dialog requires one to three non-blank buttons");
        }
    }

    public static MessageDialog ok(String title, String message) {
        return new MessageDialog(title, message, Kind.INFO, List.of("OK"));
    }
}
