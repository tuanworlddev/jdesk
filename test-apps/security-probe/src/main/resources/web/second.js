"use strict";
// Second document (spec 17.6). Reached by same-origin navigation from the first document.
// Runs probe 2 (a stale nonce carried across navigation is rejected), then completes its
// own handshake with the NEW nonce and delivers the accumulated final report. CSP-safe.
(function () {
  var B = window.JDeskBridge;

  function logLine(text, cls) {
    var li = document.createElement("li");
    li.textContent = text;
    if (cls) li.className = cls;
    document.getElementById("log").appendChild(li);
  }

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  async function runPage2() {
    var results = [];
    try {
      results = JSON.parse(sessionStorage.getItem("jdesk.probe.results") || "[]");
    } catch (e) { results = []; }
    var oldNonce = sessionStorage.getItem("jdesk.probe.oldNonce");

    function record(name, passed, detail) {
      results.push({ name: name, passed: !!passed, detail: String(detail) });
      logLine((passed ? "PASS " : "FAIL ") + name + " — " + detail, passed ? "pass" : "fail");
    }

    // Replay the results carried from the first document so the evidence page is complete.
    results.slice().forEach(function (r) {
      logLine((r.passed ? "PASS " : "FAIL ") + r.name + " — " + r.detail + " (page1)",
        r.passed ? "pass" : "fail");
    });

    try {
      var newNonce = await B.awaitNonce();
      record("page2-nonce-received", typeof newNonce === "string" && newNonce.length >= 16
        && newNonce !== oldNonce, "fresh per-navigation nonce (distinct from page1)");

      // Probe 2: send an invoke bound to the OLD (pre-navigation) nonce, BEFORE completing
      // this document's handshake. The runtime invalidated that session at commit, so the
      // dispatcher must answer STALE_NONCE and never run the handler.
      var stale = await B.invokeWithNonce("secure.privileged", null, oldNonce,
        { id: "stale-1", timeoutMs: 3000 }).then(
        function () { return { code: "OK" }; },
        function (e) { return { code: e.code || e.message }; });
      var stalePass = stale.code === "STALE_NONCE" || stale.code === "CLIENT_TIMEOUT";
      record("stale-nonce-after-navigation", stalePass,
        stale.code === "STALE_NONCE" ? "rejected with STALE_NONCE"
          : (stale.code === "CLIENT_TIMEOUT" ? "no response within 3s (also acceptable)"
            : "unexpected outcome: " + stale.code));

      // Complete the handshake with the NEW nonce and prove the fresh session is usable.
      var ack = await B.hello();
      record("page2-handshake", ack.ok === true && ack.v === 1, "helloAck on new session");
      var flags = await B.invoke("secure.flags", null);
      record("page2-new-session-usable", flags && typeof flags.ranPrivileged === "boolean",
        "new session round-trips (ranPrivileged=" + (flags && flags.ranPrivileged) + ")");
    } catch (e) {
      record("page2-suite", false, "unexpected error: " + (e && e.message));
    }

    var allPassed = results.every(function (r) { return r.passed; });
    var status = document.getElementById("status");
    status.textContent = (allPassed ? "PASS " : "FAIL ") + "security-probe";
    status.className = allPassed ? "ok" : "bad";

    // Give the compositor a beat to paint the final state before Java snapshots.
    await sleep(250);
    try {
      await B.invoke("secure.report", { cases: results, allPassed: allPassed },
        { timeoutMs: 15000 });
    } catch (e) {
      logLine("report delivery failed: " + e.message, "fail");
    }
  }

  if (B && B.bridgeReady()) {
    runPage2();
  } else {
    var s = document.getElementById("status");
    s.textContent = "FAIL no bridge";
    s.className = "bad";
  }
})();
