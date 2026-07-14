package dev.jdesk.webview.spi;

/** Document-start user scripts shared by every platform adapter. */
public final class InitScripts {

    private InitScripts() {
    }

    /**
     * Captures {@code console.*}, uncaught errors, and unhandled promise rejections and
     * forwards them over the bridge as {@code {"kind":"console"}} envelopes carrying the
     * session nonce. Messages produced before the nonce arrives are buffered (bounded)
     * so early load failures are not lost. Injected on every platform; the Java side
     * decides whether to log (dev mode or {@code -Djdesk.console.forward=true}).
     */
    public static final String CONSOLE_CAPTURE = """
            (function () {
              "use strict";
              if (window.__jdeskConsoleCapture) { return; }
              window.__jdeskConsoleCapture = true;
              var nonce = null;
              var queue = [];
              // The error listeners are installed BEFORE anything else and buffer into
              // `queue` regardless of whether the bridge object exists yet, so the very
              // first failures — a module that fails to parse or import, a <script src>
              // that 404s — are recorded even though they happen before any page script
              // (and before the hello handshake) runs. Shipping is deferred until the
              // session nonce arrives.
              function capture(level, text) {
                if (typeof text !== "string") { text = String(text); }
                if (text.length === 0) { text = "(empty)"; }
                if (text.length > 8192) { text = text.slice(0, 8192) + "..."; }
                var env = { v: 1, kind: "console", level: level, message: text };
                if (nonce === null || !window.__jdesk) {
                  if (queue.length < 100) { queue.push(env); }
                  return;
                }
                ship(env);
              }
              function ship(env) {
                try { env.nonce = nonce; window.__jdesk.post(JSON.stringify(env)); } catch (e) { }
              }
              // Capture phase (third arg true) so resource-load failures — a failed
              // module/script/link/img fetch, which do NOT bubble to window — are seen.
              window.addEventListener("error", function (event) {
                var target = event.target;
                if (target && target !== window && (target.src || target.href)) {
                  capture("error", "Failed to load " + (target.src || target.href)
                      + " (" + (target.tagName || "resource").toLowerCase() + ")");
                  return;
                }
                var detail = event.error && event.error.stack
                    ? event.error.stack
                    : (event.message || "Script error");
                capture("error", detail + " (" + (event.filename || "?")
                    + ":" + (event.lineno || 0) + ")");
              }, true);
              window.addEventListener("unhandledrejection", function (event) {
                var reason = event ? event.reason : null;
                // Defer the decision to a microtask so an app's own listener — registered in any
                // phase or order — can event.preventDefault() a benign rejection (e.g. Monaco's
                // "Canceled" worker cancellations) first. If it did, stay silent, matching the
                // browser's own default-suppression; otherwise forward it.
                var report = function () {
                  if (event && event.defaultPrevented) { return; }
                  capture("error", "Unhandled rejection: "
                      + (reason && reason.stack ? reason.stack : String(reason)));
                };
                if (typeof queueMicrotask === "function") { queueMicrotask(report); }
                else { Promise.resolve().then(report); }
              });
              function format(args) {
                var parts = [];
                for (var i = 0; i < args.length; i++) {
                  var value = args[i];
                  if (typeof value === "string") { parts.push(value); }
                  else if (value instanceof Error) { parts.push(value.stack || String(value)); }
                  else { try { parts.push(JSON.stringify(value)); } catch (e) { parts.push(String(value)); } }
                }
                return parts.join(" ");
              }
              ["log", "info", "warn", "error", "debug"].forEach(function (level) {
                var original = console[level] && console[level].bind(console);
                console[level] = function () {
                  if (original) { original.apply(null, arguments); }
                  capture(level, format(arguments));
                };
              });
              document.addEventListener("jdesk-message", function (event) {
                if (nonce !== null) { return; }
                try {
                  var message = JSON.parse(event.detail);
                  if (message && message.kind === "nonce") {
                    nonce = message.nonce;
                    var pending = queue;
                    queue = [];
                    for (var i = 0; i < pending.length; i++) { ship(pending[i]); }
                  }
                } catch (e) { }
              });
            })();
            """;
}
