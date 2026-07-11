"use strict";
// Reusable, CSP-safe JDesk bridge client shared by both probe documents. It speaks the
// uniform bridge contract: window.__jdesk.post(string) sends; incoming envelopes arrive
// as 'jdesk-message' CustomEvents on document; the per-navigation nonce is delivered by a
// {kind:'nonce'} control envelope after commit. No inline script, no eval: script-src 'self'.
window.JDeskBridge = (function () {
  var pending = new Map();
  var eventListeners = new Map();
  var nextId = 1;
  var nonceWaiters = [];
  var helloWaiter = null;

  function bridgeReady() {
    return !!(window.__jdesk && typeof window.__jdesk.post === "function");
  }

  // Raw send of an arbitrary string straight to the platform primitive (used to inject
  // malformed payloads that must never reach user code).
  function postRaw(str) {
    window.__jdesk.post(str);
  }

  function sendEnvelope(obj) {
    postRaw(JSON.stringify(obj));
  }

  function registerPending(id, timeoutMs) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () {
        pending.delete(id);
        var e = new Error("client timeout");
        e.code = "CLIENT_TIMEOUT";
        reject(e);
      }, timeoutMs || 10000);
      pending.set(id, { resolve: resolve, reject: reject, timer: timer });
    });
  }

  // Invoke using the current session nonce. Resolves with the result value; rejects with
  // an Error whose .code is the wire ErrorCode on an error result.
  function invoke(command, payload, opts) {
    opts = opts || {};
    var id = "req-" + (nextId++);
    var promise = registerPending(id, opts.timeoutMs);
    sendEnvelope({ v: 1, kind: "invoke", id: id, command: command,
      payload: payload === undefined ? null : payload, nonce: window.__jdesk.nonce });
    return promise;
  }

  // Invoke with an explicit (possibly stale/forged) nonce. Used by the stale-nonce probe.
  function invokeWithNonce(command, payload, nonce, opts) {
    opts = opts || {};
    var id = opts.id || ("stale-" + (nextId++));
    var promise = registerPending(id, opts.timeoutMs);
    sendEnvelope({ v: 1, kind: "invoke", id: id, command: command,
      payload: payload === undefined ? null : payload, nonce: nonce });
    return promise;
  }

  // Pre-register a pending id and resolve with the FULL result envelope (never rejects),
  // so a caller can observe the wire error code of an invoke it did not itself post — used
  // by the same-origin iframe probe, where the invoke is posted from the subframe context
  // but the terminal result is delivered to this (top) document.
  function expectResult(id, timeoutMs) {
    return new Promise(function (resolve) {
      var timer = setTimeout(function () {
        pending.delete(id);
        resolve({ timeout: true });
      }, timeoutMs || 4000);
      pending.set(id, { raw: true, resolve: resolve, reject: resolve, timer: timer });
    });
  }

  function onEvent(name, fn) {
    eventListeners.set(name, fn);
  }

  function awaitNonce(timeoutMs) {
    return new Promise(function (resolve, reject) {
      if (window.__jdesk && window.__jdesk.nonce) { resolve(window.__jdesk.nonce); return; }
      var timer = setTimeout(function () { reject(new Error("nonce timeout")); }, timeoutMs || 10000);
      nonceWaiters.push(function (n) { clearTimeout(timer); resolve(n); });
    });
  }

  function hello(timeoutMs) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () { reject(new Error("hello timeout")); }, timeoutMs || 10000);
      helloWaiter = function (ack) { clearTimeout(timer); resolve(ack); };
      sendEnvelope({ v: 1, kind: "hello", client: "@jdesk/security-probe",
        clientVersion: "0.1.0", nonce: window.__jdesk.nonce });
    });
  }

  document.addEventListener("jdesk-message", function (ev) {
    var msg;
    try { msg = JSON.parse(ev.detail); } catch (e) { return; }
    if (msg.kind === "nonce") {
      window.__jdesk.nonce = msg.nonce;
      var waiters = nonceWaiters.splice(0);
      waiters.forEach(function (w) { w(msg.nonce); });
    } else if (msg.kind === "helloAck") {
      if (helloWaiter) { helloWaiter(msg); helloWaiter = null; }
    } else if (msg.kind === "result") {
      var entry = pending.get(msg.id);
      if (!entry) return;
      pending.delete(msg.id);
      clearTimeout(entry.timer);
      if (entry.raw) { entry.resolve(msg); return; }
      if (msg.ok) { entry.resolve(msg.value); }
      else {
        var e = new Error(msg.error && msg.error.message);
        e.code = msg.error && msg.error.code;
        entry.reject(e);
      }
    } else if (msg.kind === "event") {
      var fn = eventListeners.get(msg.event);
      if (fn) fn(msg.payload);
    }
  });

  // Runs `promise`, records a PASS iff it rejects with the expected wire code.
  function expectCode(promise, code) {
    return promise.then(
      function () { return { passed: false, detail: "unexpectedly succeeded" }; },
      function (e) {
        return { passed: e.code === code, detail: "got " + (e.code || e.message) };
      });
  }

  return {
    bridgeReady: bridgeReady,
    postRaw: postRaw,
    sendEnvelope: sendEnvelope,
    invoke: invoke,
    invokeWithNonce: invokeWithNonce,
    expectResult: expectResult,
    onEvent: onEvent,
    awaitNonce: awaitNonce,
    hello: hello,
    expectCode: expectCode
  };
})();
