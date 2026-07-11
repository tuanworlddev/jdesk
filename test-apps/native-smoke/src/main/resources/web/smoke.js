"use strict";
(function () {
  var MAX_MESSAGE_BYTES = 1048576;
  var pending = new Map();
  var eventListeners = new Map();
  var nextId = 1;
  var results = [];

  function logLine(text, cls) {
    var li = document.createElement("li");
    li.textContent = text;
    if (cls) li.className = cls;
    document.getElementById("log").appendChild(li);
  }

  function record(name, passed, detail) {
    results.push({ name: name, passed: !!passed, detail: String(detail) });
    logLine((passed ? "PASS " : "FAIL ") + name + " — " + detail, passed ? "pass" : "fail");
  }

  function byteLength(s) {
    return new TextEncoder().encode(s).length;
  }

  function post(obj) {
    var json = JSON.stringify(obj);
    if (byteLength(json) > MAX_MESSAGE_BYTES) {
      var err = new Error("PAYLOAD_TOO_LARGE");
      err.code = "PAYLOAD_TOO_LARGE";
      throw err;
    }
    window.__jdesk.post(json);
  }

  function invoke(command, payload, opts) {
    opts = opts || {};
    return new Promise(function (resolve, reject) {
      var id = "req-" + (nextId++);
      var timer = setTimeout(function () {
        pending.delete(id);
        reject(new Error("client timeout for " + command));
      }, opts.timeoutMs || 10000);
      pending.set(id, { resolve: resolve, reject: reject, timer: timer });
      try {
        post({ v: 1, kind: "invoke", id: id, command: command,
               payload: payload === undefined ? null : payload,
               nonce: window.__jdesk.nonce });
      } catch (e) {
        clearTimeout(timer);
        pending.delete(id);
        reject(e);
      }
      if (opts.cancelAfterMs) {
        setTimeout(function () {
          post({ v: 1, kind: "cancel", id: id, nonce: window.__jdesk.nonce });
        }, opts.cancelAfterMs);
      }
    });
  }

  function onEvent(name, fn) {
    eventListeners.set(name, fn);
  }

  var nonceWaiters = [];

  function awaitNonce() {
    return new Promise(function (resolve, reject) {
      if (window.__jdesk.nonce) { resolve(window.__jdesk.nonce); return; }
      var timer = setTimeout(function () { reject(new Error("nonce timeout")); }, 10000);
      nonceWaiters.push(function (n) { clearTimeout(timer); resolve(n); });
    });
  }

  document.addEventListener("jdesk-message", function (ev) {
    var msg;
    try { msg = JSON.parse(ev.detail); } catch (e) { return; }
    if (msg.kind === "nonce") {
      window.__jdesk.nonce = msg.nonce;
      var waiters = nonceWaiters.splice(0);
      waiters.forEach(function (w) { w(msg.nonce); });
    } else if (msg.kind === "helloAck" && helloWaiter) {
      helloWaiter(msg);
      helloWaiter = null;
    } else if (msg.kind === "result") {
      var entry = pending.get(msg.id);
      if (!entry) return;
      pending.delete(msg.id);
      clearTimeout(entry.timer);
      if (msg.ok) entry.resolve(msg.value);
      else { var e = new Error(msg.error.message); e.code = msg.error.code; entry.reject(e); }
    } else if (msg.kind === "event") {
      var fn = eventListeners.get(msg.event);
      if (fn) fn(msg.payload);
    }
  });

  var helloWaiter = null;

  function hello() {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () { reject(new Error("hello timeout")); }, 10000);
      helloWaiter = function (ack) { clearTimeout(timer); resolve(ack); };
      post({ v: 1, kind: "hello", client: "@jdesk/smoke-page", clientVersion: "0.1.0",
             nonce: window.__jdesk.nonce });
    });
  }

  function expectCode(promise, code, name, detail) {
    return promise.then(
      function () { record(name, false, "unexpectedly succeeded"); },
      function (e) {
        record(name, e.code === code, detail + " (got " + (e.code || e.message) + ")");
      });
  }

  async function runProbes() {
    var runId = "unknown";
    try {
      // 0. per-navigation nonce delivered by the runtime after commit
      await awaitNonce();
      record("nonce-received", typeof window.__jdesk.nonce === "string"
             && window.__jdesk.nonce.length >= 16, "nonce present");

      // 1. handshake
      var ack = await hello();
      record("handshake", ack.ok === true && ack.v === 1, "helloAck received");

      var info = await invoke("smoke.runInfo", null);
      runId = info.runId;
      var stress = !!info.stress;

      // 2. JS -> Java typed echo
      var echo = await invoke("smoke.echo", { text: "xin chào", number: 42 });
      record("typed-echo", echo.text === "xin chào" && echo.number === 42,
             "round trip value match");

      // 4. async command off the UI thread
      record("non-ui-thread", echo.uiThread === false && echo.virtualThread === true,
             "handler thread=" + echo.threadName);

      // 3. Java -> JS event
      var eventPayload = await new Promise(function (resolve, reject) {
        var timer = setTimeout(function () { reject(new Error("event timeout")); }, 10000);
        onEvent("smoke.ping", function (payload) { clearTimeout(timer); resolve(payload); });
        invoke("smoke.emitPing", { tag: "evt-123" }).catch(reject);
      });
      record("java-event", eventPayload && eventPayload.tag === "evt-123",
             "event payload received");

      // 5. cancellation of a real sleeping command
      await expectCode(
        invoke("smoke.sleep", { millis: 20000 }, { timeoutMs: 15000, cancelAfterMs: 200 }),
        "CANCELLED", "cancellation", "sleeping command cancelled");

      // 6. unknown command
      await expectCode(invoke("smoke.doesNotExist", {}), "UNKNOWN_COMMAND",
                       "unknown-command", "rejected");

      // 7. missing capability, handler must never execute
      await expectCode(invoke("smoke.denied", { probe: true }), "CAPABILITY_DENIED",
                       "capability-denied", "rejected before handler");
      var deniedRan = await invoke("smoke.deniedHandlerRan", null);
      record("capability-denied-no-execution", deniedRan.ran === false,
             "denied handler never executed");

      // 8. oversize payload rejected deterministically (client-side limit)
      try {
        await invoke("smoke.echo", { text: "x".repeat(1100000), number: 1 });
        record("oversize-payload", false, "unexpectedly accepted");
      } catch (e) {
        record("oversize-payload", e.code === "PAYLOAD_TOO_LARGE",
               "rejected with " + (e.code || e.message));
      }

      // 9. 100 concurrent requests, correct ids and results
      var calls = [];
      for (var i = 0; i < 100; i++) {
        (function (n) {
          calls.push(invoke("smoke.echo", { text: "c" + n, number: n }).then(function (v) {
            return v.text === "c" + n && v.number === n;
          }));
        })(i);
      }
      var concurrent = await Promise.all(calls);
      var okCount = concurrent.filter(Boolean).length;
      record("concurrent-100", okCount === 100, okCount + "/100 correct");

      // 10. navigation to disallowed remote origin is blocked
      var beforeHref = location.href;
      try { window.location.href = "https://example.com/"; } catch (e) { /* blocked */ }
      await new Promise(function (r) { setTimeout(r, 600); });
      record("navigation-blocked", location.href === beforeHref && location.protocol === "jdesk:",
             "still on " + location.href);

      // 11. asset protocol: 200, 404, traversal rejected
      try {
        var okResp = await fetch("jdesk://app/asset-present.txt");
        var okText = await okResp.text();
        record("asset-200", okResp.status === 200 && okText.indexOf("jdesk-asset-ok") >= 0,
               "status " + okResp.status);
      } catch (e) { record("asset-200", false, "fetch failed: " + e.message); }
      try {
        var missing = await fetch("jdesk://app/definitely-missing.txt");
        record("asset-404", missing.status === 404, "status " + missing.status);
      } catch (e) {
        // Some engines surface load failure instead of a status for custom schemes.
        record("asset-404", true, "request failed as rejection: " + e.message);
      }
      try {
        var traversal = await fetch("jdesk://app/%2e%2e/%2e%2e/etc/passwd");
        var body = traversal.status === 200 ? await traversal.text() : "";
        record("asset-traversal", traversal.status !== 200 || body.indexOf("root:") < 0,
               "status " + traversal.status);
      } catch (e) {
        record("asset-traversal", true, "request failed as rejection: " + e.message);
      }

      // stress profile: 10,000 small IPC round trips in bounded concurrent batches
      if (stress) {
        var t0 = Date.now();
        var total = 0, wrong = 0;
        for (var batch = 0; batch < 100; batch++) {
          var group = [];
          for (var k = 0; k < 100; k++) {
            (function (n) {
              group.push(invoke("smoke.echo", { text: "s" + n, number: n },
                                { timeoutMs: 30000 }).then(function (v) {
                return v.text === "s" + n && v.number === n;
              }));
            })(batch * 100 + k);
          }
          var outcomes = await Promise.all(group);
          for (var j = 0; j < outcomes.length; j++) {
            total++;
            if (!outcomes[j]) wrong++;
          }
        }
        var elapsed = Date.now() - t0;
        record("ipc-stress-10000", total === 10000 && wrong === 0,
               total + " round trips, " + wrong + " mismatches, " + elapsed + "ms");
      }

      // 12. secondary window create/close/recreate (25 cycles in the stress profile)
      var wanted = stress ? 25 : 3;
      var cyc = await invoke("smoke.windowCycles", { cycles: wanted },
                             { timeoutMs: stress ? 175000 : 25000 });
      record("window-cycles", cyc.completed === wanted,
             cyc.completed + "/" + wanted + " cycles");

      // 13. shutdown readiness is asserted Java-side after the report.
    } catch (e) {
      record("probe-suite", false, "unexpected error: " + (e && e.message));
    }

    var allPassed = results.every(function (r) { return r.passed; });
    var status = document.getElementById("status");
    if (allPassed) {
      status.textContent = "PASS " + runId;
      status.className = "ok";
    } else {
      status.textContent = "FAIL " + runId;
      status.className = "bad";
    }
    // Give the compositor a beat to paint the final state before Java snapshots.
    await new Promise(function (r) { setTimeout(r, 250); });
    try {
      await invoke("smoke.report", { cases: results, allPassed: allPassed },
                   { timeoutMs: 15000 });
    } catch (e) {
      logLine("report delivery failed: " + e.message, "fail");
    }
  }

  if (window.__jdesk && window.__jdesk.post) {
    runProbes();
  } else {
    document.getElementById("status").textContent = "FAIL no bridge";
    document.getElementById("status").className = "bad";
  }
})();
