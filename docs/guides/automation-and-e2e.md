# Automate and E2E-test your app

JDesk has an explicitly opt-in automation endpoint — the equivalent of Electron's
`--remote-debugging-port` or `tauri-driver` — so tests, CI, and agents can drive a real
running app without OS-level screenshots and coordinate clicking.

## Start the app with automation enabled

Automation is deliberately a separate runtime-only module. The JDesk Gradle plugin adds
it to `run` and `jdeskDev`, but not to `jdeskPackage`. For a manually wired launcher, add
it only to that launcher's E2E/dev configuration:

```kotlin
val e2eRuntimeOnly by configurations.creating

dependencies {
    e2eRuntimeOnly("dev.jdesk:jdesk-automation:<jdeskVersion>")
}
```

```bash
java -Djdesk.automation=true ... your.app.Main
# or in a Gradle run task: systemProperty("jdesk.automation", "true")
```

On startup the runtime prints one line and writes a descriptor file:

```
JDESK-AUTOMATION port=52731 descriptor=/Users/you/.jdesk/automation/<appId>.json
```

The descriptor (`{pid, port, token}`, owner-only permissions; directory overridable via
`-Djdesk.automation.dir=`) carries the per-run bearer token. The server binds
127.0.0.1 only and answers nothing without `Authorization: Bearer <token>`. Without the
system property, no server exists. Production images that omit `jdesk-automation` also
omit `jdk.httpserver`; requesting automation without the provider fails startup loudly.
Request bodies are capped at 1 MiB.

## Endpoints

| Endpoint | Meaning |
| --- | --- |
| `GET /windows` | `{"windows":["main", ...]}` — open window ids |
| `POST /evaluate` `{"window":"main","script":"..."}` | Evaluates JS; returns `{"result":<parsed JSON>, "value":"<raw string>"}` — `result` is the JSON-decoded value (objects/arrays/numbers), `value` the raw string for back-compat |
| `POST /input` `{"window":"main","action":"click|type|focus|hover|key","selector":"...","text":"...","key":"..."}` | Synthesizes DOM interaction on the matched element; returns `{"ok":true/false}` |
| `GET /snapshot?window=main` | PNG screenshot of the real WebView |
| `GET /console?window=main` | Captured page console lines (`console.*`, uncaught errors, **and the earliest module/parse-load failures**) |

## A minimal E2E check

```bash
DESC=~/.jdesk/automation/com.example.app.json
PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
AUTH="Authorization: Bearer $TOKEN"

curl -s -H "$AUTH" "http://127.0.0.1:$PORT/windows"
# /evaluate returns a real JSON value under `result`:
curl -s -H "$AUTH" -X POST "http://127.0.0.1:$PORT/evaluate" \
     -d '{"window":"main","script":"({count: document.querySelectorAll(\"li\").length})"}'
# → {"result":{"count":3},"value":"{\"count\":3}"}
# /input clicks a button, then read the effect back:
curl -s -H "$AUTH" -X POST "http://127.0.0.1:$PORT/input" \
     -d '{"window":"main","action":"click","selector":"#submit"}'
curl -s -H "$AUTH" "http://127.0.0.1:$PORT/snapshot?window=main" -o shot.png
curl -s -H "$AUTH" "http://127.0.0.1:$PORT/console?window=main"
```

`/input` synthesizes real DOM events (click/type/focus/hover/key) on the element matched
by `selector` — enough to drive most flows. Note these are DOM events (`isTrusted=false`),
so real OS-level hover-CSS and IME composition are **not** reproduced; for those, use
`/evaluate` to call your app's own hooks. Because everything runs inside the real page, it
flows through the real bridge, capability checks, and command handlers.

Earliest failures too: if the page crashes in a module import or a parse error before any
script runs, `/console` still shows it — the capture script installs its error listeners
at document-start in the capture phase, so even a failed `<script type="module" src>` load
is recorded (native-smoke case `java:early-error-capture`).

Verified live on macOS (native-smoke case `java:automation-endpoint`): windows listing,
401 on missing token, real WKWebView PNG snapshot, and console-marker retrieval over
loopback HTTP.
