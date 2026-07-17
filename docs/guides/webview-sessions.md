# WebView sessions

Every window uses a browser session. Windows with the same application id and session id share
browser storage. Adapters isolate different supported ids or reject the configuration when the
native engine cannot guarantee isolation. The default is a persistent session named `default`,
which preserves the behavior of existing applications.

```java
import dev.jdesk.api.WebViewSessionConfig;
import dev.jdesk.api.WindowConfig;

var login = WebViewSessionConfig.privateSession("login")
        .userAgent("ExampleDesktop/2.4")
        .build();

WindowConfig main = WindowConfig.builder()
        .id("main")
        .entry("jdesk://app/index.html")
        .webViewSession(login)
        .build();
```

On Windows, replace `privateSession("login")` with `persistent("account-a")` when that profile must
survive application restarts.

`PERSISTENT` state is stored in an application- and session-scoped profile on Windows. On macOS and
Linux, the native default data store is retained for backward compatibility, but WebKit rejects
persistent DOM storage for the custom `jdesk://` origin with a `SecurityError`; named persistent
sessions therefore fail before the window is created. `PRIVATE` state is
memory-only on WKWebView/WebKitGTK; WebView2 uses a unique adapter-owned temporary user-data folder
and attempts to remove it after all controllers and environments close. A private id is reusable by
multiple windows during one application run, so those windows deliberately see the same cookies and
web storage. It is never reused on the next launch.

## Clear session data

Clearing is exposed on an open window and applies to every window that shares its session. Existing
documents are not reloaded automatically.

```java
import dev.jdesk.api.WebViewDataType;
import java.util.Set;

window.clearWebViewData(Set.of(
        WebViewDataType.COOKIES,
        WebViewDataType.CACHE,
        WebViewDataType.LOCAL_STORAGE));
```

`CACHE` includes the engine's memory and disk HTTP caches where separately exposed. Completion means
the native asynchronous clear operation finished; callers can then reload or navigate as needed.
For `LOCAL_STORAGE`, WebView2 uses its inclusive `ALL_DOM_STORAGE` category because the narrower
category does not clear JDesk's custom-scheme storage. This also removes IndexedDB, CacheStorage,
service workers and other DOM-accessible storage in that WebView2 profile. WKWebView and WebKitGTK
use their narrower native local-storage categories.

The implementation uses public engine APIs:

- Windows: one WebView2 environment/user-data folder per session,
  `ICoreWebView2Settings2::put_UserAgent`.
- macOS: `WKWebsiteDataStore` (`nonPersistentDataStore` or the default persistent store)
  and `WKWebView.customUserAgent`.
- Linux: one ephemeral `WebKitWebContext` per private session plus
  `webkit_settings_set_user_agent`.

## Validation and compatibility

- Session ids must match `[a-zA-Z0-9._-]{1,64}`. Separators and traversal such as `../` fail before
  native code runs.
- User agents must contain 1–1024 printable characters; CR/LF and other controls are rejected.
- Reopening one id with different settings in a running application fails instead of silently mixing
  profiles.
- WKWebView and WebKitGTK persistent stores do not expose DOM storage to the custom `jdesk://`
  origin. The macOS and Linux adapters reject named persistent sessions; their `default` profiles
  remain for compatibility but must not be used when durable DOM storage is required. Private
  sessions support in-process DOM storage. Windows supports named persistent sessions.

Cookie CRUD, proxy configuration, download decisions and origin-aware permission prompts remain
roadmap work. The current API intentionally does not expose partial adapter-specific controls.

## Engine references

- [WebView2 user-data folders](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/user-data-folder)
  [`ICoreWebView2Settings2`](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2settings2),
  and [clear browsing data](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/clear-browsing-data)
- [`WKWebsiteDataStore`](https://developer.apple.com/documentation/webkit/wkwebsitedatastore),
  [`removeData`](https://developer.apple.com/documentation/webkit/wkwebsitedatastore/removedata%28oftypes%3Amodifiedsince%3Acompletionhandler%3A%29),
  and [`WKWebView.customUserAgent`](https://developer.apple.com/documentation/webkit/wkwebview/customuseragent)
- [WebKitGTK `WebContext`](https://webkitgtk.org/reference/webkit2gtk/stable/class.WebContext.html)
  and [`WebView`](https://webkitgtk.org/reference/webkit2gtk/stable/class.WebView.html)
- [WebKitGTK `WebsiteDataManager.clear`](https://webkitgtk.org/reference/webkit2gtk/stable/method.WebsiteDataManager.clear.html)
