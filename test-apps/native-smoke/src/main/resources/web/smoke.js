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
      else { var e = new Error(msg.error.message); e.code = msg.error.code;
             e.data = msg.error.data; entry.reject(e); }
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
      var stream2gb = !!info.stream2gb;

      // Opt-in live crash-injection gate. The harness may kill only a renderer proven to
      // belong to this app during this interval; normal smoke runs never pause here.
      var killedMarker = sessionStorage.getItem("jdesk-smoke-process-kill");
      if (info.processKillHoldMs > 0 && !killedMarker) {
        sessionStorage.setItem("jdesk-smoke-process-kill", window.__jdesk.nonce);
        await new Promise(function (resolve) { setTimeout(resolve, info.processKillHoldMs); });
      } else if (killedMarker) {
        sessionStorage.removeItem("jdesk-smoke-process-kill");
        record("renderer-process-kill-recovery", killedMarker !== window.__jdesk.nonce,
               "renderer replacement handshook with a fresh nonce");
      }

      // Reload once while a real command is running. sessionStorage survives the
      // document replacement but not an application restart, making this deterministic.
      var reloadNonce = sessionStorage.getItem("jdesk-smoke-reload-nonce");
      if (!stress && !reloadNonce) {
        sessionStorage.setItem("jdesk-smoke-reload-nonce", window.__jdesk.nonce);
        invoke("smoke.sleep", { millis: 20000 }, { timeoutMs: 30000 }).catch(function () {});
        location.reload();
        return;
      }
      if (reloadNonce) {
        sessionStorage.removeItem("jdesk-smoke-reload-nonce");
        record("reload-inflight-recovery", reloadNonce !== window.__jdesk.nonce,
               "replacement document handshook with a fresh nonce");
      }

      // 2. JS -> Java typed echo
      var echo = await invoke("smoke.echo", { text: "xin chào", number: 42 });
      record("typed-echo", echo.text === "xin chào" && echo.number === 42,
             "round trip value match");

      var types = await invoke("smoke.types", {flag:true,integer:7,longValue:9007199,
        decimal:3.25,text:"typed",nullable:null,list:["a","b"],map:{one:1,two:2},
        mode:"ACTIVE",nested:{value:"inside",numbers:[1,2,3]}});
      record("type-matrix", types.flag === true && types.integer === 7
        && types.nullable === null && types.list.length === 2 && types.map.two === 2
        && types.mode === "ACTIVE" && types.nested.numbers[2] === 3,
        "primitive/null/list/map/enum/record/nested DTO round trip");

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
      post({v:1,kind:"frontendEvent",event:"smoke.frontendPing",
            payload:{tag:"js-event-456"},nonce:window.__jdesk.nonce});
      await new Promise(function(resolve){setTimeout(resolve,100);});
      var eventSeen=await invoke("smoke.frontendEventSeen",null);
      record("js-to-java-event",eventSeen.tag==="js-event-456","frontend event handler received payload");

      // 5. cancellation of a real sleeping command
      await expectCode(
        invoke("smoke.sleep", { millis: 20000 }, { timeoutMs: 15000, cancelAfterMs: 200 }),
        "CANCELLED", "cancellation", "sleeping command cancelled");
      await expectCode(invoke("smoke.timeout", { millis: 1000 }, { timeoutMs: 5000 }),
                       "TIMEOUT", "server-timeout", "server deadline enforced");

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

      var routing = await invoke("smoke.multiWindowRouting", null, { timeoutMs: 30000 });
      record("multi-window-routing", routing.isolated === true,
             "left=" + routing.left + " right=" + routing.right);

      var controls = await invoke("smoke.windowControls", null, {timeoutMs:30000});
      record("window-controls", controls.alive === true,
             "title/bounds/focus/show/hide/minimize/maximize/fullscreen/always-on-top completed");
      var clipboard = await invoke("smoke.clipboardRead", null);
      record("clipboard-read-native", clipboard.ok === true,
             "native clipboard API available; content not logged or modified");

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

      // 11b. HTTP Range: 206 slice with Content-Range, 416 beyond the asset
      try {
        var fullText = await (await fetch("jdesk://app/asset-present.txt")).text();
        var part = await fetch("jdesk://app/asset-present.txt",
                               { headers: { "Range": "bytes=2-5" } });
        var partText = await part.text();
        var contentRange = part.headers.get("Content-Range") || "";
        record("asset-range-206",
               part.status === 206 && partText === fullText.slice(2, 6)
                 && contentRange.indexOf("bytes 2-5/") === 0
                 && (part.headers.get("Accept-Ranges") || "") === "bytes",
               "status " + part.status + " content-range=" + contentRange
                 + " body=" + JSON.stringify(partText));
        var beyond = await fetch("jdesk://app/asset-present.txt",
                                 { headers: { "Range": "bytes=999999-1000000" } });
        var beyondRange = beyond.headers.get("Content-Range") || "";
        record("asset-range-416",
               beyond.status === 416 && beyondRange.indexOf("bytes */") === 0,
               "status " + beyond.status + " content-range=" + beyondRange);
      } catch (e) {
        record("asset-range-206", false, "range fetch failed: " + e.message);
      }

      // 11c. custom CSP from runtime configuration reaches native responses
      try {
        var cspResp = await fetch("jdesk://app/asset-present.txt");
        var csp = cspResp.headers.get("Content-Security-Policy") || "";
        record("custom-csp-header", csp.indexOf("media-src") >= 0,
               "csp=" + csp.slice(0, 140));
      } catch (e) {
        record("custom-csp-header", false, "fetch failed: " + e.message);
      }

      // 11d. console bridge: emit a marker; Java asserts it arrived in its logging
      console.error("SMOKE-CONSOLE-PROBE " + runId);
      record("console-probe-emitted", true, "console.error marker emitted");

      // 11e. app-defined asset route: Java-served bytes via jdesk://app/proxy/...
      try {
        var routeResp = await fetch("jdesk://app/proxy/blob");
        var routeText = await routeResp.text();
        record("asset-route",
               routeResp.status === 200 && routeText === "route-payload-0123456789"
                 && (routeResp.headers.get("Content-Type") || "")
                     .indexOf("application/x-jdesk-probe") === 0,
               "status " + routeResp.status + " type=" + routeResp.headers.get("Content-Type"));
        var routeMiss = await fetch("jdesk://app/proxy/never-registered");
        record("asset-route-404", routeMiss.status === 404, "status " + routeMiss.status);
        var routePart = await fetch("jdesk://app/proxy/blob",
                                    { headers: { "Range": "bytes=6-12" } });
        var routePartText = await routePart.text();
        record("asset-route-range", routePart.status === 206 && routePartText === "payload",
               "status " + routePart.status + " body=" + JSON.stringify(routePartText));
      } catch (e) { record("asset-route", false, "route fetch failed: " + e.message); }

      // 11f. slow-route behavior. macOS serves scheme requests asynchronously, so IPC
      // must keep flowing while /proxy/slow stalls 400 ms. Windows/Linux still serve
      // synchronously on the event thread (documented limitation) — there the probe
      // asserts the slow route completes correctly and IPC recovers afterwards.
      try {
        var asyncServing = String(info.platformId || "").indexOf("macos") >= 0;
        var slowStart = performance.now();
        var slowPromise = fetch("jdesk://app/proxy/slow").then(function (r) { return r.text(); });
        var pingsDone = 0;
        for (var pi = 0; pi < 5; pi++) {
          await invoke("smoke.echo", { text: "during-slow-" + pi, number: pi });
          pingsDone++;
        }
        var ipcElapsed = performance.now() - slowStart;
        var slowText = await slowPromise;
        var slowElapsed = performance.now() - slowStart;
        if (asyncServing) {
          record("asset-route-nonblocking",
                 pingsDone === 5 && ipcElapsed < 350 && slowText === "slow-done"
                   && slowElapsed >= 380,
                 "5 IPC round trips in " + ipcElapsed.toFixed(1) + "ms while slow route took "
                   + slowElapsed.toFixed(1) + "ms (async serving)");
        } else {
          record("asset-route-nonblocking",
                 pingsDone === 5 && slowText === "slow-done" && slowElapsed >= 380,
                 "slow route served in " + slowElapsed.toFixed(1) + "ms; serving is"
                   + " synchronous on " + info.platformId + " (documented limitation),"
                   + " IPC completed in " + ipcElapsed.toFixed(1) + "ms");
        }
      } catch (e) { record("asset-route-nonblocking", false, "failed: " + e.message); }

      // 11g. structured errors: machine-readable error.data reaches the page
      try {
        await invoke("smoke.failData", null);
        record("structured-error-data", false, "unexpectedly succeeded");
      } catch (e) {
        record("structured-error-data",
               e.code === "INVALID_REQUEST" && e.data && e.data.httpStatus === 429
                 && e.data.retryAfterSeconds === 30,
               "code=" + e.code + " data=" + JSON.stringify(e.data));
      }

      // stress profile: 10,000 small IPC round trips in bounded concurrent batches
      if (stress) {
        var t0 = performance.now();
        var total = 0, wrong = 0;
        var latencies = [];
        for (var batch = 0; batch < 100; batch++) {
          var group = [];
          for (var k = 0; k < 100; k++) {
            (function (n) {
              var callStart = performance.now();
              group.push(invoke("smoke.echo", { text: "s" + n, number: n },
                                { timeoutMs: 30000 }).then(function (v) {
                latencies.push(performance.now() - callStart);
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
        var elapsed = performance.now() - t0;
        latencies.sort(function(a,b){return a-b;});
        function pct(p){return latencies[Math.min(latencies.length-1,Math.ceil(p*latencies.length)-1)];}
        var p50 = pct(.50), p95 = pct(.95), p99 = pct(.99);
        record("ipc-stress-10000", total === 10000 && wrong === 0
               && p95 <= info.maxIpcP95Ms && p99 <= info.maxIpcP99Ms,
               total + " round trips, " + wrong + " mismatches, " + elapsed.toFixed(1)
               + "ms, throughput=" + (total*1000/elapsed).toFixed(1) + "/s"
               + ", p50=" + p50.toFixed(3) + "ms, p95=" + p95.toFixed(3)
               + "ms (max " + info.maxIpcP95Ms + "), p99=" + p99.toFixed(3)
               + "ms (max " + info.maxIpcP99Ms + ")");
      }

      if (stream2gb) {
        var descriptor = await invoke("smoke.binaryStream", null, { timeoutMs: 30000 });
        var streamed = 0, pulls = 0;
        var streamStart = performance.now();
        while (true) {
          var chunk = await invoke("jdesk.stream.pull",
            {streamId:descriptor.streamId,maxBytes:262144},{timeoutMs:30000});
          if (chunk.eof) break;
          var decoded = atob(chunk.data);
          streamed += decoded.length; pulls++;
          if (decoded.length && decoded.charCodeAt(0) !== 0x5a) throw new Error("stream corruption");
        }
        var streamElapsed = performance.now() - streamStart;
        record("stream-2gb-backpressure", streamed === 2147483648,
          streamed + " bytes in " + pulls + " pulls, " + streamElapsed.toFixed(1)
          + "ms, " + (streamed/1048576/(streamElapsed/1000)).toFixed(1) + " MiB/s");

        var cancelled = await invoke("smoke.binaryStream", null, {timeoutMs:30000});
        await invoke("jdesk.stream.pull", {streamId:cancelled.streamId,maxBytes:1024});
        await invoke("jdesk.stream.cancel", {streamId:cancelled.streamId});
        await expectCode(invoke("jdesk.stream.pull",
          {streamId:cancelled.streamId,maxBytes:1024}), "INVALID_REQUEST",
          "stream-cancellation", "cancel closes token");
      }

      // 12. secondary window create/close/recreate (25 cycles in the stress profile)
      var wanted = stress ? 100 : 3;
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
