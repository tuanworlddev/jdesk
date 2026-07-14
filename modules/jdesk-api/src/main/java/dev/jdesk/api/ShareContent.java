package dev.jdesk.api;

import java.util.List;
import java.util.Objects;

/**
 * Content handed to the OS share sheet ({@link ApplicationHandle#share(ShareContent)}): plain text
 * and/or a list of URLs (web links or {@code file:} paths). At least one must be present. The share
 * sheet lets the user pick a target app/service; no cross-platform framework offers a first-class
 * unified share, so this is a JDesk differentiator.
 */
public record ShareContent(String text, List<String> urls) {

    public ShareContent {
        text = text == null ? "" : text;
        urls = List.copyOf(Objects.requireNonNull(urls, "urls"));
        if (text.isBlank() && urls.isEmpty()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Share content needs text or at least one URL");
        }
        if (urls.stream().anyMatch(String::isBlank)) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Share URLs must be non-blank");
        }
    }

    public static ShareContent text(String text) {
        return new ShareContent(text, List.of());
    }

    public static ShareContent urls(String... urls) {
        return new ShareContent("", List.of(urls));
    }
}
