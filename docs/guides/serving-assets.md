# Serve production assets

In production, JDesk serves your web frontend over the custom `jdesk://app/` scheme — there
is no HTTP server. This guide shows you how assets load, how to lay them out, and the rules
you must follow so they resolve. It assumes you have a working app from
[Your first app](../getting-started/your-first-app.md).

## How assets load

A window's `entry` URL points at the app origin, `jdesk://app/`:

```java
WindowConfig.builder()
    .id("main")
    .entry("jdesk://app/index.html")
    .build();
```

There is no localhost server in production. Each platform adapter intercepts `jdesk://app/`
requests using its documented resource-interception API and hands them to the JDesk runtime,
which resolves them from an asset source and streams the bytes back. A request for the root
path (`/` or empty) resolves to `index.html`. See
[ADR-004](../architecture/ADR-004-no-localhost-production.md) for why there is no localhost
server.

## Where assets come from

Two asset sources back the app origin:

- **`ClasspathAssetSource`** — serves packaged assets from a fixed prefix (typically
  `web/`) on the classpath or, for a named module, through JPMS-aware resource lookup. This
  is the production path: your `src/main/resources/web/` files ship inside the app image and
  load from there.
- **`DirectoryAssetSource`** — serves assets from a directory on disk, selected when you set
  `-Djdesk.assets.dir=<path>`. This is convenient during local development: point it at
  `src/main/resources/web` and edit files without repackaging. It enforces containment on
  the real, symlink-resolved path, so a symlink pointing outside the root is never served.

Lay your frontend out under `src/main/resources/web/`:

```
src/main/resources/
  jdesk-capabilities.json
  web/
    index.html
    main.js
    style.css
```

## MIME types and cache headers

The runtime sets `Content-Type` from the file extension. Recognized types include `.html`,
`.js`/`.mjs`, `.css`, `.json`, `.svg`, `.png`, `.woff2`, `.wasm`, and more; anything
unrecognized falls back to `application/octet-stream`.

`Cache-Control` is chosen automatically:

| Asset | Cache-Control |
| --- | --- |
| `.html` / `.htm` | `no-cache` |
| content-hashed names (e.g. `app.3f9d2c1a.js`, `chunk-BX92KD01.js`) | `public, max-age=31536000, immutable` |
| everything else | `no-cache` |

If your build tool emits content-hashed filenames, they are cached aggressively and safely;
your HTML entry point is always revalidated. Serving hashed bundles is the way to get
long-lived caching.

## Path rules

Asset paths are normalized strictly and **rejected, never repaired**. A request that
violates the rules gets a deterministic `404` with no echo of the input path. Rejected
forms include:

- `..` and encoded traversal (`%2e%2e`, double-encoding)
- NUL bytes, backslashes, control characters
- absolute or drive-letter forms, `:` in a segment
- invalid percent-encoding or invalid UTF-8
- empty segments and trailing-dot/space segments
- paths longer than 2048 characters or more than 64 segments

This is what makes `jdesk://app/../../etc/passwd` and its encoded variants impossible. See
[the threat model](../security/threat-model.md) for the traversal defenses in full.

For single-page apps, an optional SPA fallback serves `index.html` for extension-less misses
so client-side routes resolve. Requests that name a file (contain a `.`) never fall back;
they 404 if missing.

## The default Content-Security-Policy

Every asset response carries a strict CSP by default:

```
default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'
```

`script-src 'self'` **blocks inline scripts and `eval`.** An inline `<script>…</script>`
block or `onclick="…"` handler will not run. Externalize all JavaScript and CSS into files
served from the app origin, and reference them:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main><!-- ... --></main>
  <script src="main.js"></script>
</body>
</html>
```

This is why the JDesk templates ship no inline script or style. Release builds go further:
the build rejects a CSP that weakens script safety with `'unsafe-inline'`, `'unsafe-eval'`,
or `'unsafe-hashes'` unless you set an explicit acknowledgement option that appears in the
build report. See the [CSP reference](../concepts/security-model.md) and
[the security model](../concepts/security-model.md).

## Range requests and large media

The resolver understands single-range `Range: bytes=...` requests and answers them with
`206 Partial Content`, a `Content-Range` header, and a body stream positioned at the
requested offset (a real seek for directory-backed assets, not a read-and-discard).
Every success response advertises `Accept-Ranges: bytes`. This is what makes `<video>`
seeking work for large packaged media: the engine's media stack (AVFoundation on macOS)
probes with ranged requests and only ever reads the byte windows it needs.

Unsatisfiable ranges (entirely beyond the asset) get `416` with `Content-Range: bytes */<size>`.
Multi-range and malformed headers are ignored and served as a full `200`, which is a
valid response to any Range request.

## App-defined asset routes (binary without base64)

Content that Java produces or proxies — cached remote images, thumbnails, exports —
should not travel as base64 data-URIs through command results (~33% inflation, 1 MiB
envelope cap, no browser caching). Register an asset route instead:

```java
JDeskApplication.builder()
    .assetRoute("proxy/images", request -> {
        Path cached = imageCache.fetch(request.path()); // may block: runs off the UI thread
        return Optional.of(AssetRoute.Response.of(cached, "image/jpeg"));
    })
```

The page then loads `<img src="jdesk://app/proxy/images/p1.jpg">` — same origin, so the
strict default CSP covers it; the response streams through the asset pipeline with Range
support, and your `Cache-Control` header (via `Response.headers`) enables engine-side
caching. Returning `Optional.empty()` yields a deterministic 404; an `IOException` maps
to a path-free 500. A route owns everything under its prefix and wins over packaged
assets there.

On macOS the scheme handler resolves and streams on background threads, so a slow route
(network fetch) never blocks the UI. On Windows/Linux serving is currently synchronous
on the event thread — keep route handlers fast there (serve from a disk cache and
prefetch via commands) until async serving lands for those adapters.

### Uploads: binary POST without base64

The same route also receives uploads. A page `fetch` with a `POST` body reaches the
handler as the exact bytes — no base64, no IPC envelope cap — through `request.body()`:

```java
JDeskApplication.builder()
    .assetRoute("upload", request -> {
        if (!request.method().equals("POST")) {
            return Optional.empty(); // 404: this route only accepts writes
        }
        byte[] bytes = request.body();          // raw upload, already bounded (see below)
        store.write(request.path(), bytes);     // may block: runs off the UI thread on macOS
        return Optional.of(AssetRoute.Response.empty()); // 200, or Response.of(json, ...) to reply
    })
```

```js
await fetch('jdesk://app/upload/report.bin', { method: 'POST', body: arrayBuffer });
```

Bodies are capped by the `jdesk.assets.maxUploadBytes` system property (default 64 MiB);
a larger upload is rejected with **413** before your handler runs, so a route never has to
defend against an unbounded buffer. A method other than `GET`/`HEAD`/`POST` gets **405**;
a `POST` that matches no route is a 404 (packaged assets are read-only).

**Platform status (honest).** On **macOS** this is live-verified end-to-end on a real
WKWebView (macOS 26.5.1, Apple Silicon): a 2 MiB `POST` arrives byte-exact — the route's
SHA-256 of `request.body()` matches an independently computed digest — a 5 MiB upload
against a 4 MiB cap returns 413 without buffering the whole body (the adapter reads only
`cap + 1` bytes), a `PUT` returns 405, and ranged `GET` still returns 206. The body is
read from `NSURLRequest.HTTPBody`. On **Windows (WebView2)** and **Linux (WebKitGTK)** the
adapters do not forward the request body yet, so a route sees an empty `body()` there;
until that lands, upload from those platforms should go through a command with chunked
transfer. Track this as PLATFORM-001.

## Customizing the Content-Security-Policy

Apps that legitimately need remote content — streaming video from a CDN, loading remote
images, calling an HTTPS API — replace the default policy on the application builder:

```java
JDeskApplication.builder()
    .id("com.example.movies")
    .contentSecurityPolicy(
        "default-src 'self'; media-src 'self' https:; img-src 'self' data: https:; "
            + "connect-src 'self' https://api.example.com; object-src 'none'; "
            + "base-uri 'none'; frame-ancestors 'none'")
    // ...
    .run(args);
```

The override replaces the `Content-Security-Policy` header on every `jdesk://app/`
response. Keep it as narrow as your app allows — prefer explicit origins
(`https://cdn.example.com`) over broad schemes (`https:`), and never widen `script-src`
beyond `'self'` without a strong reason.

To widen **one** directive without retyping (and accidentally weakening) the rest, use the
per-directive `Csp` builder, which starts from the strict default:

```java
.contentSecurityPolicy(Csp.defaults()
    .connectSrc("'self'", "wss://api.example.com")   // e.g. a WebSocket backend
    .imgSrc("'self'", "data:", "https://cdn.example.com"))
// object-src 'none', base-uri 'none', script-src 'self' … stay exactly as the default
```

Safety rails stay on: production launches (anything not started with `-Djdesk.dev=true`)
screen the override and refuse to start if it contains `'unsafe-inline'`, `'unsafe-eval'`,
or `'unsafe-hashes'`, unless you explicitly acknowledge the weakening with
`-Djdesk.security.acknowledgeUnsafeCsp=true`.

## Loading from a dev server instead

During development you can render a live dev server (Vite, etc.) instead of packaged assets.
Configure exactly one `http://127.0.0.1:<port>` or `http://localhost:<port>` origin:

```java
JDeskApplication.Builder builder = JDeskApplication.builder()
        .id("com.example.app")
        // ...
        ;
String devUrl = System.getProperty("jdesk.devUrl");
if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
    builder.devServerUrl(devUrl);
}
builder.run(args);
```

There is no silent fallback between the dev server and production assets — you get one or the
other. The dev-server origin is a development convenience only; production always loads over
`jdesk://app/`. See [ADR-004](../architecture/ADR-004-no-localhost-production.md).

> **Platform note.** WKWebView and WebKitGTK do not grant the `jdesk://` custom scheme full
> HTTPS "secure context" behavior, so some powerful web features that gate on secure
> contexts may be unavailable under the production origin. JDesk does not use private
> selectors to work around this. See
> [ADR-004](../architecture/ADR-004-no-localhost-production.md), the
> [threat model](../security/threat-model.md), and [`VERIFICATION.md`](../../VERIFICATION.md)
> for what is verified on which platform.

## Related

- [Configure and manage windows](managing-windows.md) — the `entry` URL.
- [ADR-004: No production localhost server](../architecture/ADR-004-no-localhost-production.md).
- [Security model](../concepts/security-model.md) and [CSP reference](../concepts/security-model.md).
