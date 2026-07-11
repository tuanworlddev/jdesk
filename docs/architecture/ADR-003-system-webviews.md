# ADR-003: System WebViews

Status: accepted (spec section 3, ADR-003)
Date: 2026-07-11

## Decision

- Windows: WebView2 Win32 COM API (Evergreen runtime), min Windows 10 1809.
- macOS: AppKit `NSWindow` + `WKWebView`, min macOS 13.
- Linux: GTK 3 + WebKitGTK 4.1, Ubuntu 22.04-compatible runtime.

The public Java API depends only on `jdesk-webview-spi`. Platform classes never leak
through `jdesk-api` or `jdesk-runtime`. Provider discovery uses JPMS `ServiceLoader`;
startup fails with a diagnostic when zero or multiple providers are present.
The SPI does not hard-code engine versions; the versions above are the v1 release
validation targets only.
