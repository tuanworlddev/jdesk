"use strict";
// Minimal raw JDesk bridge client (same wire protocol as the framework's smoke page):
// window.__jdesk.post(json) sends to Java, "jdesk-message" document events come back.
// Vanilla JS on purpose — no npm, no bundler, no @jdesk/client dependency.
(function () {
  var pending = new Map();
  var nextId = 1;
  var nonceWaiters = [];
  var helloWaiter = null;

  var statusEl = document.getElementById("status");
  var formEl = document.getElementById("greet-form");
  var nameEl = document.getElementById("name");
  var buttonEl = document.getElementById("greet-button");
  var answerEl = document.getElementById("answer");
  var answerMessageEl = document.getElementById("answer-message");
  var answerMetaEl = document.getElementById("answer-meta");

  function setStatus(text, isError) {
    statusEl.textContent = text;
    statusEl.className = isError ? "status error" : "status";
  }

  function post(message) {
    window.__jdesk.post(JSON.stringify(message));
  }

  function invoke(command, payload) {
    return new Promise(function (resolve, reject) {
      var id = "req-" + (nextId++);
      var timer = setTimeout(function () {
        pending.delete(id);
        reject(new Error("client timeout for " + command));
      }, 10000);
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

  function awaitNonce() {
    return new Promise(function (resolve, reject) {
      if (window.__jdesk && window.__jdesk.nonce) {
        resolve(window.__jdesk.nonce);
        return;
      }
      var timer = setTimeout(function () { reject(new Error("nonce timeout")); }, 10000);
      nonceWaiters.push(function (nonce) { clearTimeout(timer); resolve(nonce); });
    });
  }

  function hello() {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () { reject(new Error("hello timeout")); }, 10000);
      helloWaiter = function (ack) { clearTimeout(timer); resolve(ack); };
      post({ v: 1, kind: "hello", client: "hello-vanilla-page", clientVersion: "0.1.0",
             nonce: window.__jdesk.nonce });
    });
  }

  document.addEventListener("jdesk-message", function (event) {
    var message;
    try {
      message = JSON.parse(event.detail);
    } catch (e) {
      return;
    }
    if (message.kind === "nonce") {
      window.__jdesk.nonce = message.nonce;
      nonceWaiters.splice(0).forEach(function (waiter) { waiter(message.nonce); });
    } else if (message.kind === "helloAck" && helloWaiter) {
      var waiter = helloWaiter;
      helloWaiter = null;
      waiter(message);
    } else if (message.kind === "result") {
      var entry = pending.get(message.id);
      if (!entry) {
        return;
      }
      pending.delete(message.id);
      clearTimeout(entry.timer);
      if (message.ok) {
        entry.resolve(message.value);
      } else {
        var error = new Error(message.error.message);
        error.code = message.error.code;
        entry.reject(error);
      }
    }
  });

  formEl.addEventListener("submit", function (event) {
    event.preventDefault();
    buttonEl.disabled = true;
    setStatus("calling greeting.greet…", false);
    invoke("greeting.greet", { name: nameEl.value })
      .then(function (response) {
        answerMessageEl.textContent = response.message;
        answerMetaEl.textContent = "Java " + response.javaVersion
          + " · handled on " + response.threadName;
        answerEl.hidden = false;
        setStatus("round trip OK", false);
      })
      .catch(function (error) {
        setStatus("greeting.greet failed: " + (error.code || "") + " " + error.message, true);
      })
      .finally(function () {
        buttonEl.disabled = false;
        nameEl.focus();
      });
  });

  (async function init() {
    try {
      await awaitNonce();
      await hello();
      buttonEl.disabled = false;
      nameEl.focus();
      setStatus("bridge connected — say hello!", false);
    } catch (e) {
      setStatus("bridge unavailable: " + e.message, true);
    }
  })();
})();
