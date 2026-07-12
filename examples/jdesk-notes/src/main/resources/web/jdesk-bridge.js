"use strict";
// Reusable vanilla JDesk bridge client — no npm, no bundler. Copy this file into any app's
// web assets and load it before your app script. It wraps the raw wire protocol
// (window.__jdesk.post + "jdesk-message" document events: nonce -> hello -> invoke/event).
//
//   await JDeskBridge.connect();                       // handshake
//   const r = await JDeskBridge.invoke("cmd", {..});   // call a Java command
//   JDeskBridge.onEvent("my.event", p => { ... });     // Java -> page events
(function () {
  var pending = new Map();
  var nextId = 1;
  var nonceWaiters = [];
  var helloWaiter = null;
  var eventHandlers = new Map();
  var DEFAULT_TIMEOUT_MS = 30000;

  function post(message) {
    window.__jdesk.post(JSON.stringify(message));
  }

  function invoke(command, payload, timeoutMs) {
    return new Promise(function (resolve, reject) {
      var id = "req-" + (nextId++);
      var timer = setTimeout(function () {
        pending.delete(id);
        reject(new Error("client timeout for " + command));
      }, timeoutMs || DEFAULT_TIMEOUT_MS);
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
    });
  }

  function onEvent(name, handler) {
    eventHandlers.set(name, handler);
  }

  function awaitNonce() {
    return new Promise(function (resolve, reject) {
      if (window.__jdesk && window.__jdesk.nonce) { resolve(window.__jdesk.nonce); return; }
      var timer = setTimeout(function () { reject(new Error("nonce timeout")); }, 10000);
      nonceWaiters.push(function (n) { clearTimeout(timer); resolve(n); });
    });
  }

  function hello(client, version) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () { reject(new Error("hello timeout")); }, 10000);
      helloWaiter = function (ack) { clearTimeout(timer); resolve(ack); };
      post({ v: 1, kind: "hello", client: client || "jdesk-vanilla-page",
             clientVersion: version || "1.0.0", nonce: window.__jdesk.nonce });
    });
  }

  function connect(client, version) {
    return awaitNonce().then(function () { return hello(client, version); });
  }

  document.addEventListener("jdesk-message", function (event) {
    var message;
    try { message = JSON.parse(event.detail); } catch (e) { return; }
    if (message.kind === "nonce") {
      window.__jdesk.nonce = message.nonce;
      nonceWaiters.splice(0).forEach(function (w) { w(message.nonce); });
    } else if (message.kind === "helloAck" && helloWaiter) {
      var w = helloWaiter; helloWaiter = null; w(message);
    } else if (message.kind === "event") {
      var handler = eventHandlers.get(message.event);
      if (handler) { handler(message.payload); }
    } else if (message.kind === "result") {
      var entry = pending.get(message.id);
      if (!entry) { return; }
      pending.delete(message.id);
      clearTimeout(entry.timer);
      if (message.ok) {
        entry.resolve(message.value);
      } else {
        var err = new Error(message.error.message);
        err.code = message.error.code;
        entry.reject(err);
      }
    }
  });

  window.JDeskBridge = { connect: connect, invoke: invoke, onEvent: onEvent };
})();
