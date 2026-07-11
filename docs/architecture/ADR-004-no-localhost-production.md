# ADR-004: No production localhost server

Status: accepted (spec section 3, ADR-004)
Date: 2026-07-11

## Decision

Production assets load via the registered custom scheme `jdesk://app/<path>`,
implemented with each platform's documented resource-interception API:

- WebView2: `ICoreWebView2CustomSchemeRegistration` (+ allowed origins, secure flag
  where the public API supports it);
- WKWebView: `WKURLSchemeHandler`;
- WebKitGTK: `webkit_web_context_register_uri_scheme` + public security-manager APIs.

Development mode may load exactly one configured `http://127.0.0.1:<port>` or
`http://localhost:<port>` origin; no silent fallback to production assets.

## Documented limitation

WKWebView does not grant arbitrary custom schemes full HTTPS secure-context behavior.
We will NOT call private Apple selectors to work around this. Web features unavailable
under the production origin are provided by native plugins or documented unsupported.
