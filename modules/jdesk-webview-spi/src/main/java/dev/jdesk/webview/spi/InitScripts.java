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
              if (!window.__jdesk || window.__jdeskConsoleCapture) { return; }
              window.__jdeskConsoleCapture = true;
              var nonce = null;
              var queue = [];
              function ship(env) {
                try { env.nonce = nonce; window.__jdesk.post(JSON.stringify(env)); } catch (e) { }
              }
              function capture(level, text) {
                if (typeof text !== "string") { text = String(text); }
                if (text.length === 0) { text = "(empty)"; }
                if (text.length > 8192) { text = text.slice(0, 8192) + "..."; }
                var env = { v: 1, kind: "console", level: level, message: text };
                if (nonce === null) { if (queue.length < 100) { queue.push(env); } return; }
                ship(env);
              }
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
              window.addEventListener("error", function (event) {
                capture("error", (event.message || "Script error") + " ("
                    + (event.filename || "?") + ":" + (event.lineno || 0) + ")");
              });
              window.addEventListener("unhandledrejection", function (event) {
                var reason = event ? event.reason : null;
                capture("error", "Unhandled rejection: "
                    + (reason && reason.stack ? reason.stack : String(reason)));
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
