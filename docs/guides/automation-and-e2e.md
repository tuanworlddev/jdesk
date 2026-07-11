# Automate and E2E-test your app

JDesk has an explicitly opt-in automation endpoint — the equivalent of Electron's
`--remote-debugging-port` or `tauri-driver` — so tests, CI, and agents can drive a real
running app without OS-level screenshots and coordinate clicking.

## Start the app with automation enabled

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
system property, no server exists — production runs are unaffected.

## Endpoints

| Endpoint | Meaning |
| --- | --- |
| `GET /windows` | `{"windows":["main", ...]}` — open window ids |
| `POST /evaluate` `{"window":"main","script":"..."}` | Evaluates JS in the page, returns `{"value":"<string result>"}` |
| `GET /snapshot?window=main` | PNG screenshot of the real WebView |
| `GET /console?window=main` | Captured page console lines (`console.*`, uncaught errors) |

## A minimal E2E check

```bash
DESC=~/.jdesk/automation/com.example.app.json
PORT=$(python3 -c "import json;print(json.load(open('$DESC'))['port'])")
TOKEN=$(python3 -c "import json;print(json.load(open('$DESC'))['token'])")
AUTH="Authorization: Bearer $TOKEN"

curl -s -H "$AUTH" "http://127.0.0.1:$PORT/windows"
curl -s -H "$AUTH" -X POST "http://127.0.0.1:$PORT/evaluate" \
     -d '{"window":"main","script":"document.querySelector(\"h1\").textContent"}'
curl -s -H "$AUTH" "http://127.0.0.1:$PORT/snapshot?window=main" -o shot.png
curl -s -H "$AUTH" "http://127.0.0.1:$PORT/console?window=main"
```

Interactions (clicks, typing) go through `/evaluate` with DOM APIs —
`document.querySelector("button").click()`, dispatching `InputEvent`s, or calling your
app's own test hooks. Because the script runs inside the real page, everything flows
through the real bridge, capability checks, and command handlers.

Verified live on macOS (native-smoke case `java:automation-endpoint`): windows listing,
401 on missing token, real WKWebView PNG snapshot, and console-marker retrieval over
loopback HTTP.
