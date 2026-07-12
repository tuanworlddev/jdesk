"use strict";
// JDesk Notes — tabbed editor with a Files sidebar and session persistence. IPC goes through
// the reusable JDeskBridge helper (jdesk-bridge.js), loaded before this script.
(function () {
  var DIALOG_TIMEOUT_MS = 5 * 60 * 1000;

  var el = {
    tabstrip: document.getElementById("tabstrip"),
    editor: document.getElementById("editor"),
    status: document.getElementById("status"),
    counts: document.getElementById("counts"),
    sidebar: document.getElementById("sidebar"),
    sidebarPath: document.getElementById("sidebar-path"),
    fileList: document.getElementById("file-list"),
    btnSidebar: document.getElementById("btn-sidebar"),
    btnNewTab: document.getElementById("btn-newtab"),
    btnOpen: document.getElementById("btn-open"),
    btnSave: document.getElementById("btn-save"),
    btnSaveAs: document.getElementById("btn-save-as"),
    btnOpenFolder: document.getElementById("btn-open-folder"),
    sidebarPathInput: document.getElementById("sidebar-path-input"),
    recentSection: document.getElementById("recent-section"),
    recentList: document.getElementById("recent-list"),
  };
  var recent = []; // [{path, name}] most-recent-first, capped

  // ---- tab model ----
  var tabs = [];
  var activeId = null;
  var seq = 1;
  var sessionTimer = null;
  var ready = false;

  function activeTab() {
    for (var i = 0; i < tabs.length; i++) { if (tabs[i].id === activeId) { return tabs[i]; } }
    return null;
  }
  function isDirty(t) { return t.content !== t.savedContent; }

  function makeTab(path, name, content) {
    return { id: seq++, path: path || null, name: name || "Untitled",
             content: content || "", savedContent: content || "" };
  }

  function addTab(tab, activate) {
    tabs.push(tab);
    if (activate !== false) { activeId = tab.id; }
    renderTabs(); syncEditor(); render(); scheduleSession();
  }

  function setActive(id) {
    if (id === activeId) { return; }
    activeId = id;
    renderTabs(); syncEditor(); render();
    el.editor.focus();
  }

  function closeTab(id) {
    var idx = -1;
    for (var i = 0; i < tabs.length; i++) { if (tabs[i].id === id) { idx = i; break; } }
    if (idx < 0) { return; }
    var wasActive = tabs[idx].id === activeId;
    tabs.splice(idx, 1);
    if (tabs.length === 0) {
      var t = makeTab(null, "Untitled", "");
      tabs.push(t); activeId = t.id;
    } else if (wasActive) {
      activeId = tabs[Math.min(idx, tabs.length - 1)].id;
    }
    renderTabs(); syncEditor(); render(); scheduleSession();
    el.editor.focus();
  }

  // ---- rendering ----
  function renderTabs() {
    el.tabstrip.textContent = "";
    tabs.forEach(function (t) {
      var tab = document.createElement("div");
      tab.className = "tab" + (t.id === activeId ? " active" : "") + (isDirty(t) ? " dirty" : "");
      tab.title = t.path || t.name;
      var name = document.createElement("span");
      name.className = "tab-name";
      name.textContent = t.name;
      tab.appendChild(name);
      var close = document.createElement("button");
      close.className = "tab-close"; close.type = "button"; close.textContent = "×";
      close.title = "Close tab";
      close.addEventListener("click", function (e) { e.stopPropagation(); closeTab(t.id); });
      tab.appendChild(close);
      tab.addEventListener("click", function () { setActive(t.id); });
      el.tabstrip.appendChild(tab);
    });
  }

  function syncEditor() {
    var t = activeTab();
    el.editor.value = t ? t.content : "";
  }

  function render() {
    var t = activeTab();
    var text = t ? t.content : "";
    var lines = text.length === 0 ? 1 : text.split("\n").length;
    el.counts.textContent = text.length + " chars · " + lines + " lines · " + tabs.length + " tabs";
    var canAct = ready && t !== null;
    el.btnOpen.disabled = !ready;
    el.btnSaveAs.disabled = !canAct;
    el.btnSave.disabled = !canAct || (t && !isDirty(t) && t.path !== null);
  }

  function setStatus(text, kind) {
    el.status.textContent = text;
    el.status.className = "status" + (kind ? " " + kind : "");
  }

  // ---- session persistence ----
  function snapshotSession() {
    return { activeId: activeId, recent: recent, tabs: tabs.map(function (t) {
      return { path: t.path, name: t.name, content: t.content, savedContent: t.savedContent };
    }) };
  }
  function scheduleSession() {
    if (sessionTimer) { clearTimeout(sessionTimer); }
    sessionTimer = setTimeout(function () {
      invoke("notes.saveSession", { json: JSON.stringify(snapshotSession()) }).catch(function () {});
    }, 500);
  }
  function restoreSession(json) {
    var ok = false;
    try {
      if (json && json.trim()) {
        var s = JSON.parse(json);
        if (s && s.tabs && s.tabs.length) {
          tabs = s.tabs.map(function (t) {
            return { id: seq++, path: t.path || null, name: t.name || "Untitled",
                     content: t.content || "", savedContent: t.savedContent || "" };
          });
          activeId = tabs.some(function (t) { return t.id === s.activeId; }) ? s.activeId : tabs[0].id;
          if (Array.isArray(s.recent)) { recent = s.recent; }
          ok = true;
        }
      }
    } catch (e) { ok = false; }
    if (!ok) { tabs = [makeTab(null, "Untitled", "")]; activeId = tabs[0].id; }
    renderTabs(); syncEditor(); render(); renderRecent();
  }

  // ---- actions ----
  var busy = false;
  function withBusy(label, fn) {
    if (busy) { return Promise.resolve(); }
    busy = true; setStatus(label, null);
    return Promise.resolve().then(fn).catch(function (error) {
      setStatus((error.code ? error.code + " - " : "") + error.message, "error");
    }).finally(function () { busy = false; render(); el.editor.focus(); });
  }

  function newTab() { addTab(makeTab(null, "Untitled", "")); el.editor.focus(); }

  function openFile() {
    return withBusy("Opening…", function () {
      return invoke("notes.open", {}, DIALOG_TIMEOUT_MS).then(function (res) {
        if (res.cancelled) { setStatus("open cancelled"); return; }
        openInTab(res);
        setStatus("opened " + res.path, "ok");
      });
    });
  }

  function openPath(path) {
    for (var i = 0; i < tabs.length; i++) {
      if (tabs[i].path === path) { setActive(tabs[i].id); return Promise.resolve(); }
    }
    return withBusy("Opening…", function () {
      return invoke("notes.readFile", { path: path }).then(function (res) {
        openInTab(res); setStatus("opened " + res.path, "ok");
      });
    });
  }

  function openInTab(res) {
    var cur = activeTab();
    if (cur && cur.path === null && cur.content === "" && !isDirty(cur)) {
      cur.path = res.path; cur.name = res.name; cur.content = res.content; cur.savedContent = res.content;
    } else {
      tabs.push({ id: seq++, path: res.path, name: res.name, content: res.content, savedContent: res.content });
      activeId = tabs[tabs.length - 1].id;
    }
    if (res.path) { pushRecent(res.path, res.name); }
    renderTabs(); syncEditor(); render(); scheduleSession();
  }

  function pushRecent(path, name) {
    recent = recent.filter(function (r) { return r.path !== path; });
    recent.unshift({ path: path, name: name });
    if (recent.length > 10) { recent = recent.slice(0, 10); }
    renderRecent();
  }

  function renderRecent() {
    el.recentSection.hidden = recent.length === 0;
    el.recentList.textContent = "";
    recent.forEach(function (r) {
      var li = document.createElement("li");
      li.className = "file";
      li.innerHTML = '<span class="ic">🕘</span>';
      li.appendChild(document.createTextNode(r.name));
      li.title = r.path;
      li.addEventListener("click", function () { openPath(r.path); });
      el.recentList.appendChild(li);
    });
  }

  function saveActive() {
    var t = activeTab();
    if (!t) { return Promise.resolve(); }
    if (t.path === null) { return saveAsActive(); }
    return withBusy("Saving…", function () {
      return invoke("notes.save", { path: t.path, content: t.content }).then(function (res) {
        t.savedContent = t.content; t.name = res.name;
        renderTabs(); render(); scheduleSession();
        setStatus("saved " + res.path, "ok");
      });
    });
  }

  function saveAsActive() {
    var t = activeTab();
    if (!t) { return Promise.resolve(); }
    return withBusy("Save As…", function () {
      var suggested = t.name && t.name !== "Untitled" ? t.name : "untitled.txt";
      return invoke("notes.saveAs", { suggestedName: suggested, content: t.content }, DIALOG_TIMEOUT_MS)
        .then(function (res) {
          if (res.cancelled) { setStatus("save cancelled"); return; }
          t.path = res.path; t.name = res.name; t.savedContent = t.content;
          renderTabs(); render(); scheduleSession();
          setStatus("saved " + res.path, "ok");
        });
    });
  }

  // ---- sidebar (Files) ----
  function toggleSidebar() { el.sidebar.classList.toggle("hidden"); }

  function openFolder() {
    return withBusy("Open folder…", function () {
      return invoke("notes.openFolder", {}, DIALOG_TIMEOUT_MS).then(function (listing) {
        if (listing.cancelled) { setStatus("folder cancelled"); return; }
        el.sidebar.classList.remove("hidden");
        renderFolder(listing);
        setStatus("folder " + listing.path, "ok");
      });
    });
  }

  function listDir(path) {
    return withBusy("Listing…", function () {
      return invoke("notes.listDir", { path: path }).then(function (listing) { renderFolder(listing); });
    });
  }

  function parentOf(p) {
    var norm = p.replace(/[\\/]+$/, "");
    var i = Math.max(norm.lastIndexOf("\\"), norm.lastIndexOf("/"));
    return i > 0 ? norm.slice(0, i) : null;
  }

  function renderFolder(listing) {
    el.sidebarPath.textContent = listing.path;
    el.sidebarPath.title = listing.path;
    el.fileList.textContent = "";
    var parent = parentOf(listing.path);
    if (parent) {
      var up = document.createElement("li");
      up.className = "up"; up.innerHTML = '<span class="ic">⬆</span>..';
      up.addEventListener("click", function () { listDir(parent); });
      el.fileList.appendChild(up);
    }
    listing.entries.forEach(function (entry) {
      var li = document.createElement("li");
      li.className = entry.dir ? "dir" : "file";
      var icon = entry.dir ? "📁" : "📄";
      li.innerHTML = '<span class="ic">' + icon + "</span>";
      li.appendChild(document.createTextNode(entry.name));
      li.title = entry.path;
      li.addEventListener("click", function () {
        if (entry.dir) { listDir(entry.path); } else { openPath(entry.path); }
      });
      el.fileList.appendChild(li);
    });
  }

  // ---- bridge (via the reusable JDeskBridge helper in jdesk-bridge.js) ----
  var invoke = JDeskBridge.invoke;
  JDeskBridge.onEvent("notes.openPath", function (payload) {
    // Fired by single-instance activation or a file drop on the window.
    if (payload && payload.path) { openPath(payload.path); }
  });

  // ---- wiring ----
  el.btnNewTab.addEventListener("click", newTab);
  el.btnOpen.addEventListener("click", openFile);
  el.btnSave.addEventListener("click", saveActive);
  el.btnSaveAs.addEventListener("click", saveAsActive);
  el.btnSidebar.addEventListener("click", toggleSidebar);
  el.btnOpenFolder.addEventListener("click", openFolder);
  el.sidebarPathInput.addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      e.preventDefault();
      var path = el.sidebarPathInput.value.trim();
      if (path) { listDir(path); }
    }
  });
  el.editor.addEventListener("input", function () {
    var t = activeTab();
    if (t) { t.content = el.editor.value; }
    var dirtyTab = document.querySelector(".tab.active");
    if (t && dirtyTab) { dirtyTab.classList.toggle("dirty", isDirty(t)); }
    render(); scheduleSession();
  });
  document.addEventListener("keydown", function (e) {
    if (!(e.ctrlKey || e.metaKey)) { return; }
    var key = e.key.toLowerCase();
    if (key === "s" && e.shiftKey) { e.preventDefault(); saveAsActive(); }
    else if (key === "s") { e.preventDefault(); saveActive(); }
    else if (key === "o") { e.preventDefault(); openFile(); }
    else if (key === "t" || key === "n") { e.preventDefault(); newTab(); }
    else if (key === "w") { e.preventDefault(); if (activeId !== null) { closeTab(activeId); } }
  });

  (async function init() {
    try {
      await JDeskBridge.connect("jdesk-notes-page", "0.3.0");
      ready = true;
      el.editor.disabled = false;
      var res = await invoke("notes.loadSession", {});
      restoreSession(res && res.json);
      setStatus("ready · " + tabs.length + " tab(s) restored", "ok");
      el.editor.focus();
    } catch (e) {
      setStatus("bridge unavailable: " + e.message, "error");
    }
  })();
})();
