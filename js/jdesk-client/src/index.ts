// jdesk-client — static runtime for the JDesk IPC bridge (protocol v1).
//
// Implements docs/architecture/ipc-protocol.md against the uniform platform bridge:
// outgoing messages go through window.__jdesk.post(json); incoming messages arrive as
// 'jdesk-message' CustomEvents on document whose detail is the JSON envelope string.
// The per-navigation nonce is delivered via the control envelope
// {"v":1,"kind":"nonce","nonce":"<hex>"} (and may also be pre-set on window.__jdesk.nonce).

const PROTOCOL_VERSION = 1;
const CLIENT_NAME = "jdesk-client";
const CLIENT_VERSION = "0.1.0";
const MAX_MESSAGE_BYTES = 1024 * 1024;
const DEFAULT_TIMEOUT_MS = 30_000;
const HANDSHAKE_TIMEOUT_MS = 10_000;

/** Error with a stable machine-readable code (a public ErrorCode name or a client code). */
export class JDeskError extends Error {
  readonly code: string;
  /** Structured error data supplied by the Java handler (error.data), if any. */
  readonly data?: unknown;

  constructor(code: string, message?: string, data?: unknown) {
    super(message ?? code);
    this.name = "JDeskError";
    this.code = code;
    this.data = data;
  }
}

export interface InvokeOptions {
  /** Client-side timeout; a best-effort cancel envelope is sent when it fires. Default 30 000 ms. */
  timeoutMs?: number;
  /** Aborting sends a cancel envelope and rejects the call with code CANCELLED. */
  signal?: AbortSignal;
}

export interface BinaryStreamResult {
  stream: ReadableStream<Uint8Array>;
  length: number;
  contentType: string;
  fileName: string;
}

interface Bridge {
  post(message: string): void;
  nonce?: string;
}

declare global {
  interface Window {
    __jdesk?: Bridge;
  }
}

interface Envelope {
  [key: string]: unknown;
}

interface PendingEntry {
  resolve(value: unknown): void;
  reject(reason: JDeskError): void;
  timer: ReturnType<typeof setTimeout>;
  signal?: AbortSignal;
  onAbort?: () => void;
}

interface NonceWaiter {
  resolve(nonce: string): void;
  timer: ReturnType<typeof setTimeout>;
}

const pending = new Map<string, PendingEntry>();
const eventHandlers = new Map<string, Set<(payload: unknown) => void>>();
const nonceWaiters: NonceWaiter[] = [];

const sessionPrefix = randomSessionPrefix();
let invokeCounter = 0;
let currentNonce: string | null = null;
let helloDone = false;
let helloPromise: Promise<void> | null = null;
let helloWaiter: ((ack: Envelope) => void) | null = null;
let listenerInstalled = false;

function randomSessionPrefix(): string {
  const bytes = new Uint8Array(8);
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function byteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

function requireBridge(): Bridge {
  const bridge = typeof window !== "undefined" ? window.__jdesk : undefined;
  if (!bridge || typeof bridge.post !== "function") {
    throw new JDeskError("ILLEGAL_STATE", "JDesk bridge is not available on this page");
  }
  return bridge;
}

function installListener(): void {
  if (listenerInstalled || typeof document === "undefined") {
    return;
  }
  listenerInstalled = true;
  document.addEventListener("jdesk-message", (ev: Event) => {
    const detail = (ev as CustomEvent<string>).detail;
    if (typeof detail !== "string") {
      return;
    }
    let message: unknown;
    try {
      message = JSON.parse(detail);
    } catch {
      return;
    }
    if (message === null || typeof message !== "object") {
      return;
    }
    dispatch(message as Envelope);
  });
}

function dispatch(message: Envelope): void {
  if (message.v !== PROTOCOL_VERSION) {
    return;
  }
  switch (message.kind) {
    case "nonce":
      handleNonce(message.nonce);
      return;
    case "helloAck":
      if (helloWaiter) {
        const waiter = helloWaiter;
        helloWaiter = null;
        waiter(message);
      }
      return;
    case "result":
      handleResult(message);
      return;
    case "event":
      handleEvent(message);
      return;
    default:
      // Unknown kinds are ignored; the protocol may add runtime->client kinds later.
      return;
  }
}

function handleNonce(nonce: unknown): void {
  if (typeof nonce !== "string" || nonce.length === 0) {
    return;
  }
  if (currentNonce !== null && currentNonce !== nonce) {
    // Navigation reset: a new navigation session started. In-flight calls can never
    // complete (the runtime drops late results), so fail them now and re-handshake
    // lazily on the next invoke.
    rejectAllPending(new JDeskError("NAVIGATION_RESET", "Navigation reset the IPC session"));
    helloDone = false;
    helloPromise = null;
    helloWaiter = null;
  }
  currentNonce = nonce;
  const waiters = nonceWaiters.splice(0);
  for (const waiter of waiters) {
    clearTimeout(waiter.timer);
    waiter.resolve(nonce);
  }
}

function handleResult(message: Envelope): void {
  if (typeof message.id !== "string") {
    return;
  }
  const entry = takePending(message.id);
  if (!entry) {
    return; // Late result after timeout/cancel/reset; drop it.
  }
  if (message.ok === true) {
    entry.resolve(message.value);
    return;
  }
  const error = (message.error ?? {}) as Envelope;
  const code = typeof error.code === "string" ? error.code : "INTERNAL_ERROR";
  const text = typeof error.message === "string" ? error.message : code;
  entry.reject(new JDeskError(code, text, error.data));
}

function handleEvent(message: Envelope): void {
  if (typeof message.event !== "string") {
    return;
  }
  const handlers = eventHandlers.get(message.event);
  if (!handlers) {
    return;
  }
  for (const handler of Array.from(handlers)) {
    try {
      handler(message.payload);
    } catch {
      // A throwing handler must not break dispatch to other handlers.
    }
  }
}

function takePending(id: string): PendingEntry | undefined {
  const entry = pending.get(id);
  if (!entry) {
    return undefined;
  }
  pending.delete(id);
  clearTimeout(entry.timer);
  if (entry.signal && entry.onAbort) {
    entry.signal.removeEventListener("abort", entry.onAbort);
  }
  return entry;
}

function rejectAllPending(error: JDeskError): void {
  const ids = Array.from(pending.keys());
  for (const id of ids) {
    const entry = takePending(id);
    if (entry) {
      entry.reject(error);
    }
  }
}

function post(envelope: Envelope): void {
  const bridge = requireBridge();
  const json = JSON.stringify(envelope);
  if (byteLength(json) > MAX_MESSAGE_BYTES) {
    throw new JDeskError("PAYLOAD_TOO_LARGE", "Encoded message exceeds the 1 MiB limit");
  }
  bridge.post(json);
}

function sendCancel(id: string): void {
  if (currentNonce === null) {
    return;
  }
  try {
    post({ v: PROTOCOL_VERSION, kind: "cancel", id, nonce: currentNonce });
  } catch {
    // Best effort; the runtime enforces its own timeout regardless.
  }
}

function awaitNonce(): Promise<string> {
  installListener();
  if (currentNonce !== null) {
    return Promise.resolve(currentNonce);
  }
  const bridge = requireBridge();
  if (typeof bridge.nonce === "string" && bridge.nonce.length > 0) {
    currentNonce = bridge.nonce;
    return Promise.resolve(bridge.nonce);
  }
  return new Promise((resolve, reject) => {
    const waiter: NonceWaiter = {
      resolve,
      timer: setTimeout(() => {
        const index = nonceWaiters.indexOf(waiter);
        if (index >= 0) {
          nonceWaiters.splice(index, 1);
        }
      reject(new JDeskError("TIMEOUT", "Timed out waiting for the navigation nonce"));
      }, HANDSHAKE_TIMEOUT_MS),
    };
    nonceWaiters.push(waiter);
  });
}

function ensureHello(): Promise<void> {
  if (helloDone) {
    return Promise.resolve();
  }
  if (!helloPromise) {
    helloPromise = performHello().then(
      () => {
        helloDone = true;
      },
      (error: unknown) => {
        helloPromise = null;
        throw error;
      },
    );
  }
  return helloPromise;
}

async function performHello(): Promise<void> {
  const nonce = await awaitNonce();
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      helloWaiter = null;
      reject(new JDeskError("TIMEOUT", "Timed out waiting for helloAck"));
    }, HANDSHAKE_TIMEOUT_MS);
    helloWaiter = (ack) => {
      clearTimeout(timer);
      if (ack.ok === true && ack.v === PROTOCOL_VERSION && ack.nonce === nonce) {
        resolve();
      } else {
        const error = (ack.error ?? {}) as Envelope;
        const code = typeof error.code === "string"
          ? error.code
          : "PROTOCOL_VERSION_UNSUPPORTED";
        const message = typeof error.message === "string"
          ? error.message
          : "Runtime rejected the handshake";
        reject(new JDeskError(code, message));
      }
    };
    try {
      post({
        v: PROTOCOL_VERSION,
        kind: "hello",
        client: CLIENT_NAME,
        clientVersion: CLIENT_VERSION,
        nonce,
      });
    } catch (error) {
      clearTimeout(timer);
      helloWaiter = null;
      reject(error);
    }
  });
}

/**
 * Invokes one command and resolves with its result value. Performs the hello handshake
 * lazily on first use (and again after a navigation reset). Rejects with
 * {@link JDeskError} using the runtime's public error codes, or the client-side codes
 * PAYLOAD_TOO_LARGE, TIMEOUT, CANCELLED, and NAVIGATION_RESET.
 */
export async function invoke(
  command: string,
  payload?: unknown,
  options?: InvokeOptions,
): Promise<unknown> {
  installListener();
  const signal = options?.signal;
  if (signal?.aborted) {
    throw new JDeskError("CANCELLED", "Call aborted before it was sent");
  }
  await ensureHello();
  if (signal?.aborted) {
    throw new JDeskError("CANCELLED", "Call aborted before it was sent");
  }
  const nonce = currentNonce;
  if (nonce === null) {
    throw new JDeskError("ILLEGAL_STATE", "No navigation nonce is available");
  }
  const id = `${sessionPrefix}-${++invokeCounter}`;
  const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new JDeskError("INVALID_REQUEST", "timeoutMs must be a positive finite number");
  }
  return new Promise<unknown>((resolve, reject) => {
    const entry: PendingEntry = {
      resolve,
      reject,
      timer: setTimeout(() => {
        if (takePending(id)) {
          sendCancel(id);
          reject(new JDeskError("TIMEOUT", `Command ${command} timed out after ${timeoutMs} ms`));
        }
      }, timeoutMs),
      signal,
    };
    pending.set(id, entry);
    if (signal) {
      entry.onAbort = () => {
        if (takePending(id)) {
          sendCancel(id);
          reject(new JDeskError("CANCELLED", `Command ${command} was aborted`));
        }
      };
      signal.addEventListener("abort", entry.onAbort);
    }
    try {
      post({
        v: PROTOCOL_VERSION,
        kind: "invoke",
        id,
        command,
        payload: payload === undefined ? null : payload,
        nonce,
      });
    } catch (error) {
      if (takePending(id)) {
        reject(
          error instanceof JDeskError
            ? error
            : new JDeskError("INTERNAL_ERROR", String(error)),
        );
      }
    }
  });
}

/** Invokes a command returning Java BinaryStream and exposes pull backpressure. */
export async function invokeStream(
  command: string,
  payload?: unknown,
  options?: InvokeOptions & { chunkBytes?: number },
): Promise<BinaryStreamResult> {
  const descriptor = await invoke(command, payload, options) as Envelope;
  const streamId = descriptor.streamId;
  if (typeof streamId !== "string" || typeof descriptor.length !== "number"
      || typeof descriptor.contentType !== "string" || typeof descriptor.fileName !== "string") {
    throw new JDeskError("SERIALIZATION_ERROR", "Command did not return a binary stream");
  }
  const chunkBytes = options?.chunkBytes ?? 256 * 1024;
  if (!Number.isInteger(chunkBytes) || chunkBytes < 1 || chunkBytes > 256 * 1024) {
    throw new JDeskError("INVALID_REQUEST", "chunkBytes must be between 1 and 262144");
  }
  const stream = new ReadableStream<Uint8Array>({
    async pull(controller) {
      const chunk = await invoke("jdesk.stream.pull",
        { streamId, maxBytes: chunkBytes }, options) as Envelope;
      if (chunk.eof === true) { controller.close(); return; }
      if (typeof chunk.data !== "string") {
        controller.error(new JDeskError("SERIALIZATION_ERROR", "Invalid stream chunk"));
        return;
      }
      const binary = atob(chunk.data);
      const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
      controller.enqueue(bytes);
    },
    async cancel() {
      try { await invoke("jdesk.stream.cancel", { streamId }, options); } catch { /* best effort */ }
    },
  });
  return { stream, length: descriptor.length,
    contentType: descriptor.contentType, fileName: descriptor.fileName };
}

/**
 * Subscribes to a runtime event ({"v":1,"kind":"event",...}). Returns an unsubscribe
 * function; calling it removes the handler and releases the event's slot when it was
 * the last handler.
 */
export function on(event: string, handler: (payload: unknown) => void): () => void {
  installListener();
  let handlers = eventHandlers.get(event);
  if (!handlers) {
    handlers = new Set();
    eventHandlers.set(event, handlers);
  }
  handlers.add(handler);
  return () => {
    const current = eventHandlers.get(event);
    if (!current) {
      return;
    }
    current.delete(handler);
    if (current.size === 0) {
      eventHandlers.delete(event);
    }
  };
}

/** Emits a registered Java-side frontend event. Delivery is asynchronous/no-ack. */
export async function emit(event: string, payload?: unknown): Promise<void> {
  if (!event || event.length > 128) throw new JDeskError("INVALID_REQUEST", "Invalid event name");
  await ensureHello();
  if (currentNonce === null) throw new JDeskError("ILLEGAL_STATE", "No navigation nonce is available");
  post({v:PROTOCOL_VERSION,kind:"frontendEvent",event,
    payload:payload===undefined?null:payload,nonce:currentNonce});
}

// Install eagerly when a document exists so the nonce control envelope is never missed,
// even when the first invoke happens long after navigation.
installListener();
