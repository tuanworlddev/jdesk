package dev.jdesk.api;

/** Browser data categories that can be cleared for an entire WebView session. */
public enum WebViewDataType {
    /** HTTP cookies, including HttpOnly cookies. */
    COOKIES,
    /** In-memory and on-disk HTTP caches. */
    CACHE,
    /**
     * Origin local storage. WebView2 clears its inclusive DOM-storage group so custom-scheme
     * local storage is covered; that engine can therefore also remove IndexedDB, CacheStorage,
     * service workers and related DOM-accessible storage.
     */
    LOCAL_STORAGE
}
