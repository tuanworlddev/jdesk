"use strict";
// JDesk Notes — vanilla bridge client (same wire protocol as hello-vanilla / the smoke page).
// window.__jdesk.post(json) sends to Java; "jdesk-message" document events come back.
(function () {
  var pending = new Map();
  var nextId = 1;
  var nonceWaiters = [];
  var helloWaiter = null;

  // Dialog commands (Open / Save As) are app-modal and may stay open while the user
  // browses; give them a generous timeout. Plain writes resolve quickly.
  var DIALOG_TIMEOUT_MS = 5 * 60 * 1000;
  var CALL_TIMEOUT_MS = 30 * 1000;

  var el = {
    btnNew: document.getElementById("btn-new"),
    btnOpen: document.getElementById("btn-open"),
    btnSave: document.getElementById("btn-save"),
    btnSaveAs: document.getElementById("btn-save-as"),
    title: document.getElementById("doc-title"),
    editor: document.getElementById("editor"),
    status: document.getElementById("status"),
    counts: document.getElementById("counts"),
  };

  // ---- document state ----
  var currentPath = null;      // absolute path, or null when untitled
  var currentName = "Untitled";
  var savedContent = "";       // last persisted/loaded content
  var busy = false;

  function isDirty() {
    return el.editor.value !== savedContent;
  }

  function setStatus(text, kind) {
    el.status.textContent = text;
    el.status.className = "status" + (kind ? " " + kind : "");
  }

  function render() {
    el.title.textContent = currentName;
    el.title.classList.toggle("dirty", isDirty());
    var text = el.editor.value;
    var lines = text.length === 0 ? 1 : text.split("\n").length;
    el.counts.textContent = text.length + " chars · " + lines + " lines";
    var ready = !busy && !el.editor.disabled;
    el.btnNew.disabled = !ready;
    el.btnOpen.disabled = !ready;
    el.btnSaveAs.disabled = !ready;
    // Save is meaningful when there is something new to persist.
    el.btnSave.disabled = !ready || (!isDirty() && currentPath !== null);
  }

  // ---- bridge plumbing ----
  function post(message) {
    window.__jdesk.post(JSON.stringify(message));
  }

  function invoke(command, payload, timeoutMs) {
    return new Promise(function (resolve, reject) {
      var id = "req-" + (nextId++);
      var timer = setTimeout(function () {
        pending.delete(id);
        reject(new Error("client timeout for " + command));
      }, timeoutMs || CALL_TIMEOUT_MS);
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
      post({ v: 1, kind: "hello", client: "jdesk-notes-page", clientVersion: "0.1.0",
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
      var w = helloWaiter;
      helloWaiter = null;
      w(message);
    } else if (message.kind === "result") {
      var entry = pending.get(message.id);
      if (!entry) { return; }
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

  // ---- actions ----
  function withBusy(label, fn) {
    if (busy) { return; }
    busy = true;
    render();
    setStatus(label, null);
    Promise.resolve()
      .then(fn)
      .catch(function (error) {
        setStatus((error.code ? error.code + " — " : "") + error.message, "error");
      })
      .finally(function () {
        busy = false;
        render();
        el.editor.focus();
      });
  }

  function doNew() {
    withBusy("New note", function () {
      el.editor.value = "";
      currentPath = null;
      currentName = "Untitled";
      savedContent = "";
      setStatus("new note", "ok");
    });
  }

  function doOpen() {
    withBusy("Opening…", function () {
      return invoke("notes.open", {}, DIALOG_TIMEOUT_MS).then(function (res) {
        if (res.cancelled) {
          setStatus("open cancelled", null);
          return;
        }
        el.editor.value = res.content;
        currentPath = res.path;
        currentName = res.name;
        savedContent = res.content;
        setStatus("opened " + res.path, "ok");
      });
    });
  }

  function doSave() {
    if (currentPath === null) {
      doSaveAs();
      return;
    }
    withBusy("Saving…", function () {
      var content = el.editor.value;
      return invoke("notes.save", { path: currentPath, content: content }).then(function (res) {
        savedContent = content;
        currentName = res.name;
        setStatus("saved " + res.path, "ok");
      });
    });
  }

  function doSaveAs() {
    withBusy("Save As…", function () {
      var content = el.editor.value;
      var suggested = currentName && currentName !== "Untitled" ? currentName : "untitled.txt";
      return invoke("notes.saveAs", { suggestedName: suggested, content: content },
          DIALOG_TIMEOUT_MS).then(function (res) {
        if (res.cancelled) {
          setStatus("save cancelled", null);
          return;
        }
        currentPath = res.path;
        currentName = res.name;
        savedContent = content;
        setStatus("saved " + res.path, "ok");
      });
    });
  }

  el.btnNew.addEventListener("click", doNew);
  el.btnOpen.addEventListener("click", doOpen);
  el.btnSave.addEventListener("click", doSave);
  el.btnSaveAs.addEventListener("click", doSaveAs);
  el.editor.addEventListener("input", render);

  document.addEventListener("keydown", function (e) {
    if (!(e.ctrlKey || e.metaKey)) { return; }
    var key = e.key.toLowerCase();
    if (key === "s" && e.shiftKey) { e.preventDefault(); doSaveAs(); }
    else if (key === "s") { e.preventDefault(); doSave(); }
    else if (key === "o") { e.preventDefault(); doOpen(); }
    else if (key === "n") { e.preventDefault(); doNew(); }
  });

  (async function init() {
    try {
      await awaitNonce();
      await hello();
      el.editor.disabled = false;
      render();
      setStatus("ready — New · Open · Save · Save As", "ok");
      el.editor.focus();
    } catch (e) {
      setStatus("bridge unavailable: " + e.message, "error");
    }
  })();
})();
