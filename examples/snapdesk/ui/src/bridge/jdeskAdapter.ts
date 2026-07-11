// JDesk protocol adapter — maps the SnapDesk `JavaBridge` contract onto the
// real JDesk IPC mechanism (`window.__jdesk` + @jdesk/client).
//
// The JDesk framework does NOT inject `window.javaBridge`; frontends call
// registered Java commands through `invoke(command, payload)` and receive
// pushed events through `on(event, handler)`. This adapter keeps the app 100%
// contract-based: the Java side only has to register the `snapdesk.*` commands
// and emit the `snapdesk.*` events listed in ../../NATIVE_API.md.
//
// Callback-style contract methods translate as:
//   onMessage       <- event "snapdesk.ipc"              payload { channel, payload }
//   watchFile       <- invoke "snapdesk.fs.watch" + event "snapdesk.fs.changed"
//   onBeforeClose   <- event "snapdesk.window.closeRequested" { token }
//                      -> invoke "snapdesk.window.closeResponse" { token, allow }
//   setTray clicks  <- delivered as "snapdesk.ipc" messages on channel "tray"

import { invoke, on } from "@jdesk/client";
import {
  BridgeError,
  toBridgeError,
  type BeforeCloseHandler,
  type DirEntry,
  type Encoding,
  type FileChange,
  type IpcHandler,
  type JavaBridge,
  type MessageBoxOptions,
  type MessageBoxResult,
  type OpenFileDialogOptions,
  type OpenFolderDialogOptions,
  type SaveFileDialogOptions,
  type TrayMenuItem,
  type Unsubscribe,
  type WindowOptions,
} from "./javaBridge";

async function call<T>(command: string, payload?: unknown): Promise<T> {
  try {
    return (await invoke(command, payload)) as T;
  } catch (e) {
    throw toBridgeError(e);
  }
}

class JDeskClientAdapter implements JavaBridge {
  private readonly ipcHandlers = new Map<string, Set<IpcHandler>>();
  private readonly fileWatchers = new Map<string, (change: FileChange) => void>();
  private beforeClose: BeforeCloseHandler | null = null;

  constructor() {
    on("snapdesk.ipc", (raw) => {
      const msg = raw as { channel?: string; payload?: unknown };
      if (typeof msg?.channel !== "string") return;
      const handlers = this.ipcHandlers.get(msg.channel);
      if (!handlers) return;
      for (const h of Array.from(handlers)) {
        try {
          h(msg.payload);
        } catch {
          /* keep dispatching */
        }
      }
    });
    on("snapdesk.fs.changed", (raw) => {
      const ev = raw as { watchId?: string; path?: string; kind?: FileChange["kind"] };
      if (typeof ev?.watchId !== "string" || typeof ev.path !== "string") return;
      this.fileWatchers.get(ev.watchId)?.({ path: ev.path, kind: ev.kind ?? "modified" });
    });
    on("snapdesk.window.closeRequested", (raw) => {
      const ev = raw as { token?: string };
      if (typeof ev?.token !== "string") return;
      void this.answerCloseRequest(ev.token);
    });
  }

  private async answerCloseRequest(token: string): Promise<void> {
    let allow = true;
    try {
      allow = this.beforeClose ? await this.beforeClose() : true;
    } catch {
      allow = true; // a broken handler must never make the window unclosable
    }
    await call("snapdesk.window.closeResponse", { token, allow });
  }

  // File I/O
  readFile(path: string, options?: { encoding?: Encoding }): Promise<string> {
    return call("snapdesk.fs.readFile", { path, encoding: options?.encoding ?? "utf8" });
  }
  writeFile(path: string, contents: string, options?: { encoding?: Encoding }): Promise<void> {
    return call("snapdesk.fs.writeFile", {
      path,
      contents,
      encoding: options?.encoding ?? "utf8",
    });
  }
  listDir(path: string): Promise<DirEntry[]> {
    return call("snapdesk.fs.listDir", { path });
  }
  deleteFile(path: string): Promise<void> {
    return call("snapdesk.fs.deleteFile", { path });
  }
  fileExists(path: string): Promise<boolean> {
    return call("snapdesk.fs.fileExists", { path });
  }
  createDir(path: string): Promise<void> {
    return call("snapdesk.fs.createDir", { path });
  }
  rename(oldPath: string, newPath: string): Promise<void> {
    return call("snapdesk.fs.rename", { oldPath, newPath });
  }
  watchFile(path: string, onChange: (change: FileChange) => void): Unsubscribe {
    let watchId: string | null = null;
    let cancelled = false;
    void call<{ watchId: string }>("snapdesk.fs.watch", { path }).then(
      ({ watchId: id }) => {
        if (cancelled) {
          void call("snapdesk.fs.unwatch", { watchId: id }).catch(() => {});
          return;
        }
        watchId = id;
        this.fileWatchers.set(id, onChange);
      },
      (e) => console.warn("[bridge] watchFile failed:", toBridgeError(e).message),
    );
    return () => {
      cancelled = true;
      if (watchId !== null) {
        this.fileWatchers.delete(watchId);
        void call("snapdesk.fs.unwatch", { watchId }).catch(() => {});
      }
    };
  }

  // Dialogs
  openFileDialog(options?: OpenFileDialogOptions): Promise<string[] | null> {
    return call("snapdesk.dialog.openFile", options ?? {});
  }
  saveFileDialog(options?: SaveFileDialogOptions): Promise<string | null> {
    return call("snapdesk.dialog.saveFile", options ?? {});
  }
  openFolderDialog(options?: OpenFolderDialogOptions): Promise<string | null> {
    return call("snapdesk.dialog.openFolder", options ?? {});
  }
  showMessageBox(options: MessageBoxOptions): Promise<MessageBoxResult> {
    return call("snapdesk.dialog.messageBox", options);
  }

  // Window
  createWindow(name: string, url: string, options?: WindowOptions): Promise<void> {
    return call("snapdesk.window.create", { name, url, options: options ?? {} });
  }
  closeWindow(name?: string): Promise<void> {
    return call("snapdesk.window.close", { name: name ?? null });
  }
  minimize(): Promise<void> {
    return call("snapdesk.window.minimize", {});
  }
  maximize(): Promise<void> {
    return call("snapdesk.window.maximize", {});
  }
  toggleMaximize(): Promise<void> {
    return call("snapdesk.window.toggleMaximize", {});
  }
  setAlwaysOnTop(flag: boolean): Promise<void> {
    return call("snapdesk.window.setAlwaysOnTop", { flag });
  }
  isMaximized(): Promise<boolean> {
    return call("snapdesk.window.isMaximized", {});
  }
  startWindowDrag(): Promise<void> {
    return call("snapdesk.window.startDrag", {});
  }
  showWindow(name?: string): Promise<void> {
    return call("snapdesk.window.show", { name: name ?? null });
  }

  // IPC
  sendToWindow(target: string, channel: string, payload: unknown): Promise<void> {
    return call("snapdesk.ipc.send", { target, channel, payload });
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

  // System
  showNotification(title: string, body?: string): Promise<void> {
    return call("snapdesk.system.notify", { title, body: body ?? null });
  }
  setTray(items: TrayMenuItem[]): Promise<void> {
    return call("snapdesk.system.setTray", { items });
  }
  writeClipboard(text: string): Promise<void> {
    return call("snapdesk.system.writeClipboard", { text });
  }
  readClipboard(): Promise<string> {
    return call("snapdesk.system.readClipboard", {});
  }
  quitApp(): Promise<void> {
    return call("snapdesk.app.quit", {});
  }

  // Settings
  async getSetting(key: string): Promise<string | null> {
    return call("snapdesk.settings.get", { key });
  }
  setSetting(key: string, value: string): Promise<void> {
    return call("snapdesk.settings.set", { key, value });
  }

  // Lifecycle
  onBeforeClose(handler: BeforeCloseHandler): Unsubscribe {
    this.beforeClose = handler;
    return () => {
      if (this.beforeClose === handler) this.beforeClose = null;
    };
  }
}

export function createJDeskAdapter(): JavaBridge {
  if (!window.__jdesk) {
    throw new BridgeError("ILLEGAL_STATE", "window.__jdesk is not available");
  }
  return new JDeskClientAdapter();
}
