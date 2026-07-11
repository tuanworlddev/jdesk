// =============================================================================
// SnapDesk — Bridge contract (Bước 0)
// =============================================================================
// This file is the single source of truth for every native capability the app
// needs. It contains:
//
//   1. `JavaBridge`         — TypeScript interface describing ALL native methods
//                             the app expects on `window.javaBridge`.
//   2. `JavaBridgeWrapper`  — async wrapper around `window.javaBridge`; every
//                             call returns a Promise and rejects with a typed
//                             `BridgeError`.
//   3. `MockBridge`         — in-memory/localStorage fallback so the app runs
//                             in a plain browser (dev/test) with no native side.
//   4. A comment block at the end listing every native method the framework
//      must implement (signature checklist — see also ../../NATIVE_API.md).
//
// Backend selection (see `getBridge()` at the bottom):
//   window.javaBridge present  -> JavaBridgeWrapper   (this contract, verbatim)
//   window.__jdesk present     -> JDeskClientAdapter  (./jdeskAdapter.ts) — the
//                                 JDesk framework does NOT inject `javaBridge`;
//                                 it exposes `invoke(command)`/`on(event)` via
//                                 @jdesk/client. The adapter maps this contract
//                                 1:1 onto `snapdesk.*` commands (mapping table
//                                 in NATIVE_API.md).
//   neither                    -> MockBridge          (browser dev fallback)
// =============================================================================

// -----------------------------------------------------------------------------
// Shared types
// -----------------------------------------------------------------------------

export type Encoding = "utf8" | "base64";

export interface DirEntry {
  name: string;
  /** Absolute path (platform-native separators are fine; the app treats it as opaque). */
  path: string;
  isDirectory: boolean;
  /** Size in bytes; 0 for directories. */
  size: number;
  /** Last-modified time, epoch milliseconds. */
  modifiedAt: number;
}

export interface FileFilter {
  name: string;
  /** Extensions without dot, e.g. ["md", "txt"]. */
  extensions: string[];
}

export interface OpenFileDialogOptions {
  title?: string;
  filters?: FileFilter[];
  multiple?: boolean;
}

export interface SaveFileDialogOptions {
  title?: string;
  defaultPath?: string;
  filters?: FileFilter[];
}

export interface OpenFolderDialogOptions {
  title?: string;
}

export interface MessageBoxOptions {
  type?: "info" | "warning" | "error" | "question";
  title: string;
  message: string;
  detail?: string;
  /** Button labels, left-to-right. Defaults to ["OK"]. */
  buttons?: string[];
  /** Index into `buttons` focused by default. */
  defaultButton?: number;
  /** Index returned when the dialog is dismissed (Esc / close). */
  cancelButton?: number;
}

export interface MessageBoxResult {
  /** Index into `MessageBoxOptions.buttons` of the pressed button. */
  buttonIndex: number;
}

export interface WindowOptions {
  width?: number;
  height?: number;
  minWidth?: number;
  minHeight?: number;
  /** Frameless window with no OS titlebar (the app draws its own). */
  frameless?: boolean;
  alwaysOnTop?: boolean;
  title?: string;
}

export interface TrayMenuItem {
  /** Stable id reported back on click via IPC channel "tray": { itemId }. */
  id: string;
  label: string;
  /** When true, renders a separator; id/label are ignored. */
  separator?: boolean;
}

export type Unsubscribe = () => void;

/**
 * Before-close handler. Return `true` to allow the window to close,
 * `false` to keep it open. May return a Promise (e.g. to ask the user
 * through a native dialog first) — the native side must await it.
 *
 * NOTE for the mock (browser) backend: `beforeunload` cannot await a
 * Promise, so returning a Promise from the handler makes the mock show the
 * browser's generic "leave site?" dialog instead. Return `true`
 * synchronously on the clean/fast path so closing an unmodified window is
 * never blocked.
 */
export type BeforeCloseHandler = () => boolean | Promise<boolean>;

export type IpcHandler = (payload: unknown) => void;

/** Payload delivered by watchFile subscriptions. */
export interface FileChange {
  path: string;
  kind: "modified" | "deleted";
}

// -----------------------------------------------------------------------------
// 1. The contract — everything the app needs from the native side
// -----------------------------------------------------------------------------

export interface JavaBridge {
  // --- File I/O -------------------------------------------------------------
  readFile(path: string, options?: { encoding?: Encoding }): Promise<string>;
  writeFile(
    path: string,
    contents: string,
    options?: { encoding?: Encoding },
  ): Promise<void>;
  listDir(path: string): Promise<DirEntry[]>;
  deleteFile(path: string): Promise<void>;
  fileExists(path: string): Promise<boolean>;
  /** Recursive mkdir; succeeds when the directory already exists. */
  createDir(path: string): Promise<void>;
  /** Rename/move a file or directory. Fails if the target exists. */
  rename(oldPath: string, newPath: string): Promise<void>;
  /**
   * Watch a single file for modification/deletion. Returns an unsubscribe
   * function. The callback runs on the JS thread of the subscribing window.
   */
  watchFile(path: string, onChange: (change: FileChange) => void): Unsubscribe;

  // --- Native dialogs ---------------------------------------------------------
  /** Resolves with absolute paths, or null when the user cancels. */
  openFileDialog(options?: OpenFileDialogOptions): Promise<string[] | null>;
  saveFileDialog(options?: SaveFileDialogOptions): Promise<string | null>;
  openFolderDialog(options?: OpenFolderDialogOptions): Promise<string | null>;
  showMessageBox(options: MessageBoxOptions): Promise<MessageBoxResult>;

  // --- Window management ------------------------------------------------------
  /**
   * Open a named secondary window. `url` may be relative to the app root
   * (dev server or jdesk://app/). Calling it again with an existing name
   * focuses that window instead of creating a duplicate.
   */
  createWindow(name: string, url: string, options?: WindowOptions): Promise<void>;
  /** Close the named window; omitting `name` closes the calling window. */
  closeWindow(name?: string): Promise<void>;
  minimize(): Promise<void>;
  maximize(): Promise<void>;
  toggleMaximize(): Promise<void>;
  setAlwaysOnTop(flag: boolean): Promise<void>;
  isMaximized(): Promise<boolean>;
  /**
   * Begin an interactive OS window drag (called on titlebar mousedown).
   * Needed because system WebViews have no CSS `app-region: drag`.
   */
  startWindowDrag(): Promise<void>;
  /** Show + focus a window (used by the tray "Show" item). Defaults to caller. */
  showWindow(name?: string): Promise<void>;

  // --- IPC between windows ------------------------------------------------------
  /**
   * Send a message to another window. `target` is a window name, or "*" to
   * broadcast to every window except the sender.
   */
  sendToWindow(target: string, channel: string, payload: unknown): Promise<void>;
  /** Receive messages addressed to this window (or broadcast). */
  onMessage(channel: string, handler: IpcHandler): Unsubscribe;

  // --- System ---------------------------------------------------------------
  showNotification(title: string, body?: string): Promise<void>;
  /**
   * Replace the tray icon menu. Item clicks are delivered to the MAIN window
   * as an IPC message on channel "tray" with payload { itemId: string }.
   */
  setTray(items: TrayMenuItem[]): Promise<void>;
  writeClipboard(text: string): Promise<void>;
  readClipboard(): Promise<string>;
  /** Quit the whole application (all windows; before-close handlers still run). */
  quitApp(): Promise<void>;

  // --- Settings (persisted key/value store, survives restarts) ----------------
  getSetting(key: string): Promise<string | null>;
  setSetting(key: string, value: string): Promise<void>;

  // --- Lifecycle ---------------------------------------------------------------
  /**
   * Intercept the calling window's close request (titlebar X, Cmd/Alt+F4,
   * app quit). See BeforeCloseHandler. Last registration wins.
   */
  onBeforeClose(handler: BeforeCloseHandler): Unsubscribe;
}

declare global {
  interface Window {
    /** Injected by the native framework before app scripts run (if available). */
    javaBridge?: JavaBridge;
  }
}

// -----------------------------------------------------------------------------
// Typed error every bridge backend rejects with
// -----------------------------------------------------------------------------

export class BridgeError extends Error {
  readonly code: string;

  constructor(code: string, message?: string) {
    super(message ?? code);
    this.name = "BridgeError";
    this.code = code;
  }
}

export function toBridgeError(error: unknown): BridgeError {
  if (error instanceof BridgeError) return error;
  if (
    error !== null &&
    typeof error === "object" &&
    "code" in error &&
    typeof (error as { code: unknown }).code === "string"
  ) {
    return new BridgeError(
      (error as { code: string }).code,
      error instanceof Error ? error.message : String(error),
    );
  }
  return new BridgeError(
    "INTERNAL_ERROR",
    error instanceof Error ? error.message : String(error),
  );
}

// -----------------------------------------------------------------------------
// 2. Async wrapper around window.javaBridge
// -----------------------------------------------------------------------------
// Guarantees: every method returns a real Promise (even if the injected object
// returns synchronously or throws synchronously), and every failure is a
// BridgeError with a stable `code`.

function wrapCall<T>(fn: () => T | Promise<T>): Promise<T> {
  try {
    return Promise.resolve(fn()).catch((e) => {
      throw toBridgeError(e);
    });
  } catch (e) {
    return Promise.reject(toBridgeError(e));
  }
}

export class JavaBridgeWrapper implements JavaBridge {
  constructor(private readonly raw: JavaBridge) {}

  // File I/O
  readFile(path: string, options?: { encoding?: Encoding }) {
    return wrapCall(() => this.raw.readFile(path, options));
  }
  writeFile(path: string, contents: string, options?: { encoding?: Encoding }) {
    return wrapCall(() => this.raw.writeFile(path, contents, options));
  }
  listDir(path: string) {
    return wrapCall(() => this.raw.listDir(path));
  }
  deleteFile(path: string) {
    return wrapCall(() => this.raw.deleteFile(path));
  }
  fileExists(path: string) {
    return wrapCall(() => this.raw.fileExists(path));
  }
  createDir(path: string) {
    return wrapCall(() => this.raw.createDir(path));
  }
  rename(oldPath: string, newPath: string) {
    return wrapCall(() => this.raw.rename(oldPath, newPath));
  }
  watchFile(path: string, onChange: (change: FileChange) => void): Unsubscribe {
    return this.raw.watchFile(path, onChange);
  }

  // Dialogs
  openFileDialog(options?: OpenFileDialogOptions) {
    return wrapCall(() => this.raw.openFileDialog(options));
  }
  saveFileDialog(options?: SaveFileDialogOptions) {
    return wrapCall(() => this.raw.saveFileDialog(options));
  }
  openFolderDialog(options?: OpenFolderDialogOptions) {
    return wrapCall(() => this.raw.openFolderDialog(options));
  }
  showMessageBox(options: MessageBoxOptions) {
    return wrapCall(() => this.raw.showMessageBox(options));
  }

  // Window
  createWindow(name: string, url: string, options?: WindowOptions) {
    return wrapCall(() => this.raw.createWindow(name, url, options));
  }
  closeWindow(name?: string) {
    return wrapCall(() => this.raw.closeWindow(name));
  }
  minimize() {
    return wrapCall(() => this.raw.minimize());
  }
  maximize() {
    return wrapCall(() => this.raw.maximize());
  }
  toggleMaximize() {
    return wrapCall(() => this.raw.toggleMaximize());
  }
  setAlwaysOnTop(flag: boolean) {
    return wrapCall(() => this.raw.setAlwaysOnTop(flag));
  }
  isMaximized() {
    return wrapCall(() => this.raw.isMaximized());
  }
  startWindowDrag() {
    return wrapCall(() => this.raw.startWindowDrag());
  }
  showWindow(name?: string) {
    return wrapCall(() => this.raw.showWindow(name));
  }

  // IPC
  sendToWindow(target: string, channel: string, payload: unknown) {
    return wrapCall(() => this.raw.sendToWindow(target, channel, payload));
  }
  onMessage(channel: string, handler: IpcHandler): Unsubscribe {
    return this.raw.onMessage(channel, handler);
  }

  // System
  showNotification(title: string, body?: string) {
    return wrapCall(() => this.raw.showNotification(title, body));
  }
  setTray(items: TrayMenuItem[]) {
    return wrapCall(() => this.raw.setTray(items));
  }
  writeClipboard(text: string) {
    return wrapCall(() => this.raw.writeClipboard(text));
  }
  readClipboard() {
    return wrapCall(() => this.raw.readClipboard());
  }
  quitApp() {
    return wrapCall(() => this.raw.quitApp());
  }

  // Settings
  getSetting(key: string) {
    return wrapCall(() => this.raw.getSetting(key));
  }
  setSetting(key: string, value: string) {
    return wrapCall(() => this.raw.setSetting(key, value));
  }

  // Lifecycle
  onBeforeClose(handler: BeforeCloseHandler): Unsubscribe {
    return this.raw.onBeforeClose(handler);
  }
}

// -----------------------------------------------------------------------------
// 3. Mock backend — lets the whole app run in a plain browser
// -----------------------------------------------------------------------------
// - Filesystem: in-memory map persisted to localStorage, seeded with a demo
//   workspace on first run.
// - Dialogs: promise-based DOM overlays (no window.alert/confirm — those block
//   automation and look nothing like native dialogs).
// - Windows: window.open on the same origin; "IPC" rides a BroadcastChannel so
//   popout sync is actually testable across browser tabs/windows.
// - Settings: localStorage.

const MOCK_FS_KEY = "snapdesk.mockfs.v1";
const MOCK_SETTINGS_KEY = "snapdesk.mocksettings.v1";
const MOCK_WORKSPACE = "/SnapDesk Demo";

interface MockFile {
  c: string; // contents (utf8 text, or base64 when e === "base64")
  e: Encoding;
  m: number; // modifiedAt epoch ms
}

interface MockFsData {
  dirs: string[];
  files: Record<string, MockFile>;
}

function mockWindowName(): string {
  const params = new URLSearchParams(window.location.search);
  return params.get("win") ?? "main";
}

function seedFs(): MockFsData {
  const now = 0; // stable seed; real timestamps appear as soon as files are written
  const note = (body: string): MockFile => ({ c: body, e: "utf8", m: now });
  return {
    dirs: [
      MOCK_WORKSPACE,
      `${MOCK_WORKSPACE}/Inbox`,
      `${MOCK_WORKSPACE}/Snippets`,
      `${MOCK_WORKSPACE}/_assets`,
    ],
    files: {
      [`${MOCK_WORKSPACE}/Inbox/welcome.md`]: note(
        "# Welcome to SnapDesk\n\nYou are running with the **mock bridge** " +
          "(no native side detected). Everything is stored in localStorage.\n\n" +
          "- Create collections in the left sidebar\n" +
          "- Notes live in the middle column\n" +
          "- Edit here, with live preview (toggle edit / preview / split)\n\n" +
          "```ts\nconst hello = 'snippets get syntax-ish styling';\n```\n",
      ),
      [`${MOCK_WORKSPACE}/Inbox/todo.md`]: note(
        "# Todo\n\n- [ ] Try the command palette (Ctrl/Cmd+K)\n- [ ] Pop a note out into its own window\n- [ ] Drop a .md file here to import it\n",
      ),
      [`${MOCK_WORKSPACE}/Snippets/git-cheatsheet.md`]: note(
        "# Git cheatsheet\n\n```bash\ngit switch -c feature\ngit rebase -i main\ngit push --force-with-lease\n```\n",
      ),
    },
  };
}

function normPath(p: string): string {
  return p.replace(/\\/g, "/").replace(/\/+$/, "") || "/";
}

function parentOf(p: string): string {
  const n = normPath(p);
  const idx = n.lastIndexOf("/");
  return idx <= 0 ? "/" : n.slice(0, idx);
}

function baseName(p: string): string {
  const n = normPath(p);
  return n.slice(n.lastIndexOf("/") + 1);
}

/** Promise-based DOM overlay used for the mock's message boxes and pickers. */
function mockOverlay(options: {
  title: string;
  message: string;
  detail?: string;
  buttons: string[];
  defaultButton: number;
  cancelButton: number;
  input?: { placeholder?: string; value?: string };
}): Promise<{ buttonIndex: number; inputValue: string }> {
  return new Promise((resolve) => {
    const backdrop = document.createElement("div");
    backdrop.setAttribute(
      "style",
      "position:fixed;inset:0;background:rgba(0,0,0,.45);z-index:9999;" +
        "display:flex;align-items:center;justify-content:center;font-family:system-ui,sans-serif;",
    );
    const box = document.createElement("div");
    box.setAttribute(
      "style",
      "background:#1f2430;color:#e8eaf0;border:1px solid #3a4152;border-radius:10px;" +
        "min-width:320px;max-width:480px;padding:16px 18px;box-shadow:0 12px 40px rgba(0,0,0,.5);",
    );
    const h = document.createElement("div");
    h.textContent = options.title;
    h.setAttribute("style", "font-weight:600;margin-bottom:6px;");
    const m = document.createElement("div");
    m.textContent = options.message;
    m.setAttribute("style", "font-size:13px;opacity:.9;white-space:pre-wrap;");
    box.append(h, m);
    if (options.detail) {
      const d = document.createElement("div");
      d.textContent = options.detail;
      d.setAttribute("style", "font-size:12px;opacity:.6;margin-top:4px;white-space:pre-wrap;");
      box.append(d);
    }
    let input: HTMLInputElement | null = null;
    if (options.input) {
      input = document.createElement("input");
      input.value = options.input.value ?? "";
      input.placeholder = options.input.placeholder ?? "";
      input.setAttribute(
        "style",
        "width:100%;margin-top:10px;background:#141821;color:inherit;border:1px solid #3a4152;" +
          "border-radius:6px;padding:6px 8px;font-size:13px;outline:none;box-sizing:border-box;",
      );
      box.append(input);
    }
    const row = document.createElement("div");
    row.setAttribute(
      "style",
      "display:flex;gap:8px;justify-content:flex-end;margin-top:14px;",
    );
    const done = (buttonIndex: number) => {
      backdrop.remove();
      resolve({ buttonIndex, inputValue: input?.value ?? "" });
    };
    options.buttons.forEach((label, i) => {
      const b = document.createElement("button");
      b.textContent = label;
      const primary = i === options.defaultButton;
      b.setAttribute(
        "style",
        `padding:6px 14px;border-radius:6px;font-size:13px;cursor:pointer;border:1px solid ${
          primary ? "#5b8def" : "#3a4152"
        };background:${primary ? "#3667d6" : "transparent"};color:inherit;`,
      );
      b.addEventListener("click", () => done(i));
      row.append(b);
    });
    box.append(row);
    backdrop.append(box);
    backdrop.addEventListener("keydown", (e) => {
      if (e.key === "Escape") done(options.cancelButton);
      if (e.key === "Enter") done(options.defaultButton);
    });
    document.body.append(backdrop);
    (input ?? row.querySelector("button"))?.focus();
  });
}

interface MockIpcEnvelope {
  target: string;
  channel: string;
  payload: unknown;
  from: string;
}

export class MockBridge implements JavaBridge {
  private readonly windowName = mockWindowName();
  private readonly ipc = new BroadcastChannel("snapdesk-mock-ipc");
  private readonly fsEvents = new BroadcastChannel("snapdesk-mock-fs");
  private readonly ipcHandlers = new Map<string, Set<IpcHandler>>();
  private readonly watchers = new Map<string, Set<(c: FileChange) => void>>();
  private beforeClose: BeforeCloseHandler | null = null;
  private maximized = false;

  constructor() {
    if (localStorage.getItem(MOCK_FS_KEY) === null) {
      localStorage.setItem(MOCK_FS_KEY, JSON.stringify(seedFs()));
    }
    this.ipc.addEventListener("message", (ev: MessageEvent<MockIpcEnvelope>) => {
      const msg = ev.data;
      if (msg.from === this.windowName) return;
      if (msg.target !== "*" && msg.target !== this.windowName) return;
      this.dispatchIpc(msg.channel, msg.payload);
    });
    this.fsEvents.addEventListener("message", (ev: MessageEvent<FileChange>) => {
      this.notifyWatchers(ev.data, false);
    });
    window.addEventListener("beforeunload", (e) => {
      if (!this.beforeClose) return;
      let verdict: boolean | Promise<boolean>;
      try {
        verdict = this.beforeClose();
      } catch {
        return;
      }
      // A Promise means "I need to ask the user" — beforeunload cannot await,
      // so fall back to the browser's generic leave-site prompt.
      if (verdict === false || verdict instanceof Promise) {
        e.preventDefault();
      }
    });
  }

  private dispatchIpc(channel: string, payload: unknown): void {
    const handlers = this.ipcHandlers.get(channel);
    if (!handlers) return;
    for (const h of Array.from(handlers)) {
      try {
        h(payload);
      } catch {
        /* one bad handler must not break the rest */
      }
    }
  }

  private loadFs(): MockFsData {
    try {
      return JSON.parse(localStorage.getItem(MOCK_FS_KEY) ?? "") as MockFsData;
    } catch {
      return seedFs();
    }
  }

  private saveFs(data: MockFsData): void {
    localStorage.setItem(MOCK_FS_KEY, JSON.stringify(data));
  }

  private notifyWatchers(change: FileChange, broadcast: boolean): void {
    const subs = this.watchers.get(change.path);
    if (subs) {
      for (const cb of Array.from(subs)) cb(change);
    }
    if (broadcast) this.fsEvents.postMessage(change);
  }

  // --- File I/O ---------------------------------------------------------------

  async readFile(path: string, options?: { encoding?: Encoding }): Promise<string> {
    const file = this.loadFs().files[normPath(path)];
    if (!file) throw new BridgeError("NOT_FOUND", `No such file: ${path}`);
    const want = options?.encoding ?? "utf8";
    if (want === file.e) return file.c;
    // utf8 <-> base64 conversion for the odd cross-encoding read
    if (want === "base64") return btoa(unescape(encodeURIComponent(file.c)));
    return decodeURIComponent(escape(atob(file.c)));
  }

  async writeFile(
    path: string,
    contents: string,
    options?: { encoding?: Encoding },
  ): Promise<void> {
    const p = normPath(path);
    const fs = this.loadFs();
    const parent = parentOf(p);
    if (!fs.dirs.includes(parent)) {
      throw new BridgeError("NOT_FOUND", `Parent directory missing: ${parent}`);
    }
    fs.files[p] = { c: contents, e: options?.encoding ?? "utf8", m: Date.now() };
    this.saveFs(fs);
    this.notifyWatchers({ path: p, kind: "modified" }, true);
  }

  async listDir(path: string): Promise<DirEntry[]> {
    const p = normPath(path);
    const fs = this.loadFs();
    if (!fs.dirs.includes(p)) {
      throw new BridgeError("NOT_FOUND", `No such directory: ${path}`);
    }
    const entries: DirEntry[] = [];
    for (const dir of fs.dirs) {
      if (parentOf(dir) === p) {
        entries.push({ name: baseName(dir), path: dir, isDirectory: true, size: 0, modifiedAt: 0 });
      }
    }
    for (const [filePath, file] of Object.entries(fs.files)) {
      if (parentOf(filePath) === p) {
        entries.push({
          name: baseName(filePath),
          path: filePath,
          isDirectory: false,
          size: file.c.length,
          modifiedAt: file.m,
        });
      }
    }
    return entries.sort((a, b) => a.name.localeCompare(b.name));
  }

  async deleteFile(path: string): Promise<void> {
    const p = normPath(path);
    const fs = this.loadFs();
    if (!(p in fs.files)) throw new BridgeError("NOT_FOUND", `No such file: ${path}`);
    delete fs.files[p];
    this.saveFs(fs);
    this.notifyWatchers({ path: p, kind: "deleted" }, true);
  }

  async fileExists(path: string): Promise<boolean> {
    const p = normPath(path);
    const fs = this.loadFs();
    return p in fs.files || fs.dirs.includes(p);
  }

  async createDir(path: string): Promise<void> {
    const p = normPath(path);
    const fs = this.loadFs();
    const chain: string[] = [];
    let cursor = p;
    while (cursor !== "/" && !fs.dirs.includes(cursor)) {
      chain.unshift(cursor);
      cursor = parentOf(cursor);
    }
    fs.dirs.push(...chain);
    this.saveFs(fs);
  }

  async rename(oldPath: string, newPath: string): Promise<void> {
    const from = normPath(oldPath);
    const to = normPath(newPath);
    const fs = this.loadFs();
    if (to in fs.files || fs.dirs.includes(to)) {
      throw new BridgeError("ALREADY_EXISTS", `Target exists: ${newPath}`);
    }
    if (from in fs.files) {
      fs.files[to] = fs.files[from];
      delete fs.files[from];
    } else if (fs.dirs.includes(from)) {
      fs.dirs = fs.dirs.map((d) =>
        d === from || d.startsWith(from + "/") ? to + d.slice(from.length) : d,
      );
      for (const key of Object.keys(fs.files)) {
        if (key.startsWith(from + "/")) {
          fs.files[to + key.slice(from.length)] = fs.files[key];
          delete fs.files[key];
        }
      }
    } else {
      throw new BridgeError("NOT_FOUND", `No such path: ${oldPath}`);
    }
    this.saveFs(fs);
    this.notifyWatchers({ path: from, kind: "deleted" }, true);
    this.notifyWatchers({ path: to, kind: "modified" }, true);
  }

  watchFile(path: string, onChange: (change: FileChange) => void): Unsubscribe {
    const p = normPath(path);
    let subs = this.watchers.get(p);
    if (!subs) {
      subs = new Set();
      this.watchers.set(p, subs);
    }
    subs.add(onChange);
    return () => {
      const current = this.watchers.get(p);
      current?.delete(onChange);
      if (current?.size === 0) this.watchers.delete(p);
    };
  }

  // --- Dialogs -----------------------------------------------------------------

  async openFileDialog(options?: OpenFileDialogOptions): Promise<string[] | null> {
    const { buttonIndex, inputValue } = await mockOverlay({
      title: options?.title ?? "Open file",
      message: "Mock file picker — enter an absolute path inside the mock FS.",
      buttons: ["Open", "Cancel"],
      defaultButton: 0,
      cancelButton: 1,
      input: { value: `${MOCK_WORKSPACE}/Inbox/welcome.md` },
    });
    if (buttonIndex !== 0 || !inputValue.trim()) return null;
    return [normPath(inputValue.trim())];
  }

  async saveFileDialog(options?: SaveFileDialogOptions): Promise<string | null> {
    const { buttonIndex, inputValue } = await mockOverlay({
      title: options?.title ?? "Save file",
      message: "Mock save dialog — enter a target path.",
      buttons: ["Save", "Cancel"],
      defaultButton: 0,
      cancelButton: 1,
      input: { value: options?.defaultPath ?? `${MOCK_WORKSPACE}/untitled.md` },
    });
    if (buttonIndex !== 0 || !inputValue.trim()) return null;
    return normPath(inputValue.trim());
  }

  async openFolderDialog(options?: OpenFolderDialogOptions): Promise<string | null> {
    const { buttonIndex, inputValue } = await mockOverlay({
      title: options?.title ?? "Choose folder",
      message: "Mock folder picker — the demo workspace is pre-filled.",
      buttons: ["Choose", "Cancel"],
      defaultButton: 0,
      cancelButton: 1,
      input: { value: MOCK_WORKSPACE },
    });
    if (buttonIndex !== 0 || !inputValue.trim()) return null;
    const path = normPath(inputValue.trim());
    await this.createDir(path); // mock convenience: picking a new path creates it
    return path;
  }

  async showMessageBox(options: MessageBoxOptions): Promise<MessageBoxResult> {
    const buttons = options.buttons?.length ? options.buttons : ["OK"];
    const { buttonIndex } = await mockOverlay({
      title: options.title,
      message: options.message,
      detail: options.detail,
      buttons,
      defaultButton: options.defaultButton ?? 0,
      cancelButton: options.cancelButton ?? Math.max(0, buttons.length - 1),
    });
    return { buttonIndex };
  }

  // --- Window ------------------------------------------------------------------

  async createWindow(name: string, url: string, options?: WindowOptions): Promise<void> {
    const w = options?.width ?? 720;
    const h = options?.height ?? 560;
    const opened = window.open(url, name, `width=${w},height=${h},popup=yes`);
    if (!opened) {
      throw new BridgeError("PERMISSION_DENIED", "Popup blocked by the browser");
    }
    opened.focus();
  }

  async closeWindow(name?: string): Promise<void> {
    if (name && name !== this.windowName) {
      // The mock cannot reach into other browser windows; ask them politely.
      this.ipc.postMessage({
        target: name,
        channel: "__mock-close",
        payload: null,
        from: this.windowName,
      } satisfies MockIpcEnvelope);
      return;
    }
    window.close();
  }

  async minimize(): Promise<void> {
    console.info("[mock bridge] minimize() — no-op in the browser");
  }

  async maximize(): Promise<void> {
    this.maximized = true;
    console.info("[mock bridge] maximize() — no-op in the browser");
  }

  async toggleMaximize(): Promise<void> {
    this.maximized = !this.maximized;
    console.info("[mock bridge] toggleMaximize() — no-op in the browser");
  }

  async setAlwaysOnTop(flag: boolean): Promise<void> {
    console.info(`[mock bridge] setAlwaysOnTop(${flag}) — no-op in the browser`);
  }

  async isMaximized(): Promise<boolean> {
    return this.maximized;
  }

  async startWindowDrag(): Promise<void> {
    // Browsers move their own windows; nothing to do.
  }

  async showWindow(name?: string): Promise<void> {
    if (!name || name === this.windowName) window.focus();
  }

  // --- IPC ---------------------------------------------------------------------

  async sendToWindow(target: string, channel: string, payload: unknown): Promise<void> {
    this.ipc.postMessage({ target, channel, payload, from: this.windowName });
  }

  onMessage(channel: string, handler: IpcHandler): Unsubscribe {
    let handlers = this.ipcHandlers.get(channel);
    if (!handlers) {
      handlers = new Set();
      this.ipcHandlers.set(channel, handlers);
    }
    handlers.add(handler);
    return () => {
      const current = this.ipcHandlers.get(channel);
      current?.delete(handler);
      if (current?.size === 0) this.ipcHandlers.delete(channel);
    };
  }

  // --- System --------------------------------------------------------------------

  async showNotification(title: string, body?: string): Promise<void> {
    if (typeof Notification === "undefined") {
      console.info(`[mock bridge] notification: ${title} — ${body ?? ""}`);
      return;
    }
    if (Notification.permission === "default") {
      await Notification.requestPermission();
    }
    if (Notification.permission === "granted") {
      new Notification(title, { body });
    } else {
      console.info(`[mock bridge] notification: ${title} — ${body ?? ""}`);
    }
  }

  async setTray(items: TrayMenuItem[]): Promise<void> {
    console.info(
      "[mock bridge] setTray:",
      items.map((i) => (i.separator ? "---" : i.label)).join(" | "),
    );
  }

  async writeClipboard(text: string): Promise<void> {
    await navigator.clipboard.writeText(text);
  }

  async readClipboard(): Promise<string> {
    return navigator.clipboard.readText();
  }

  async quitApp(): Promise<void> {
    await this.sendToWindow("*", "__mock-close", null);
    window.close();
  }

  // --- Settings --------------------------------------------------------------------

  private loadSettings(): Record<string, string> {
    try {
      return JSON.parse(localStorage.getItem(MOCK_SETTINGS_KEY) ?? "{}");
    } catch {
      return {};
    }
  }

  async getSetting(key: string): Promise<string | null> {
    return this.loadSettings()[key] ?? null;
  }

  async setSetting(key: string, value: string): Promise<void> {
    const all = this.loadSettings();
    all[key] = value;
    localStorage.setItem(MOCK_SETTINGS_KEY, JSON.stringify(all));
  }

  // --- Lifecycle -------------------------------------------------------------------

  onBeforeClose(handler: BeforeCloseHandler): Unsubscribe {
    this.beforeClose = handler;
    // Also honour close requests sent through mock IPC (quitApp / closeWindow).
    const off = this.onMessage("__mock-close", async () => {
      const verdict = this.beforeClose ? await this.beforeClose() : true;
      if (verdict) window.close();
    });
    return () => {
      this.beforeClose = null;
      off();
    };
  }
}

// -----------------------------------------------------------------------------
// Backend selection
// -----------------------------------------------------------------------------

export type BridgeMode = "javaBridge" | "jdesk" | "mock";

let cached: { bridge: JavaBridge; mode: BridgeMode } | null = null;

/**
 * Returns the process-wide bridge instance. Detection order:
 * window.javaBridge -> window.__jdesk (JDesk protocol adapter) -> mock.
 */
export function getBridge(): { bridge: JavaBridge; mode: BridgeMode } {
  if (cached) return cached;
  if (typeof window !== "undefined" && window.javaBridge) {
    cached = { bridge: new JavaBridgeWrapper(window.javaBridge), mode: "javaBridge" };
  } else if (typeof window !== "undefined" && window.__jdesk) {
    // Lazy import would be async; the adapter module is tiny, import statically.
    cached = { bridge: createJDeskAdapter(), mode: "jdesk" };
  } else {
    cached = { bridge: new MockBridge(), mode: "mock" };
    console.info(
      "[SnapDesk] window.javaBridge / window.__jdesk not found — using the mock bridge (localStorage).",
    );
  }
  return cached;
}

import { createJDeskAdapter } from "./jdeskAdapter";

// =============================================================================
// 4. NATIVE METHOD CHECKLIST — what the framework must implement
// =============================================================================
// Full parameter/return documentation and the JDesk `snapdesk.*` command
// mapping live in ../../NATIVE_API.md. Signatures (all Promise-based):
//
// File I/O
//   readFile(path: string, options?: { encoding?: "utf8" | "base64" }): Promise<string>
//   writeFile(path: string, contents: string, options?: { encoding?: "utf8" | "base64" }): Promise<void>
//   listDir(path: string): Promise<DirEntry[]>            // { name, path, isDirectory, size, modifiedAt }
//   deleteFile(path: string): Promise<void>
//   fileExists(path: string): Promise<boolean>
//   createDir(path: string): Promise<void>                // [addition] recursive mkdir — needed to create collections
//   rename(oldPath: string, newPath: string): Promise<void> // [addition] needed to rename notes
//   watchFile(path: string, onChange: (c: { path, kind: "modified"|"deleted" }) => void): () => void
//
// Dialogs
//   openFileDialog(options?: { title?, filters?, multiple? }): Promise<string[] | null>
//   saveFileDialog(options?: { title?, defaultPath?, filters? }): Promise<string | null>
//   openFolderDialog(options?: { title? }): Promise<string | null>
//   showMessageBox(options: { type?, title, message, detail?, buttons?, defaultButton?, cancelButton? }): Promise<{ buttonIndex: number }>
//
// Window
//   createWindow(name: string, url: string, options?: WindowOptions): Promise<void>
//   closeWindow(name?: string): Promise<void>
//   minimize(): Promise<void>
//   maximize(): Promise<void>
//   toggleMaximize(): Promise<void>
//   setAlwaysOnTop(flag: boolean): Promise<void>
//   isMaximized(): Promise<boolean>
//   startWindowDrag(): Promise<void>                      // [addition] frameless titlebar drag (no CSS app-region in system WebViews)
//   showWindow(name?: string): Promise<void>              // [addition] tray "Show" needs to focus the main window
//
// IPC
//   sendToWindow(target: string /* name or "*" */, channel: string, payload: unknown): Promise<void>
//   onMessage(channel: string, handler: (payload: unknown) => void): () => void
//
// System
//   showNotification(title: string, body?: string): Promise<void>
//   setTray(items: { id, label, separator? }[]): Promise<void>   // clicks -> IPC channel "tray", payload { itemId }
//   writeClipboard(text: string): Promise<void>
//   readClipboard(): Promise<string>
//   quitApp(): Promise<void>                              // [addition] tray "Quit"
//
// Settings
//   getSetting(key: string): Promise<string | null>
//   setSetting(key: string, value: string): Promise<void>
//
// Lifecycle
//   onBeforeClose(handler: () => boolean | Promise<boolean>): () => void
//     // true = allow close; the native side must await Promise results.
// =============================================================================
