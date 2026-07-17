package dev.jdesk.api;

/** Browser data categories that can be cleared for an entire WebView session. */
public enum WebViewDataType {
    /** HTTP cookies, including HttpOnly cookies. */
    COOKIES,
    /** In-memory and on-disk HTTP caches. */
    CACHE,
    /** Origin local storage. */
    LOCAL_STORAGE
}
