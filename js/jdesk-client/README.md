# jdesk-client

Static TypeScript runtime for the JDesk IPC protocol v1
(`docs/architecture/ipc-protocol.md`): nonce lifecycle, lazy `hello` handshake,
`invoke` with unique ids and a 1 MiB client-side size limit, per-call timeout and
`AbortSignal` cancellation (both send a `cancel` envelope), navigation-reset handling
(in-flight calls reject with `NAVIGATION_RESET`, the handshake is redone lazily), an
event subscription API (`on(event, handler)` returns an unsubscribe function), and
pull-based binary streaming (`invokeStream` returns a `ReadableStream<Uint8Array>`).

Zero dependencies, ES2020 modules. Build with `npm run build` (plain `tsc`); building is
not required for code generation — `jdesk-codegen` emits `types.ts`/`commands.ts` that
import `invoke` from this package (default output: `ui/src/generated/`).

```ts
import { commands } from "./generated/commands";

const response = await commands.greeting.greet({ name: "Tuan" });
```

Failures reject with `JDeskError`: `code` is a stable machine-readable error code, and
`data` carries any structured details the Java handler attached
(`JDeskException(code, message, details, cause)` → `error.data`):

```ts
import { JDeskError } from "jdesk-client";

try {
  await commands.shop.sync({});
} catch (e) {
  if (e instanceof JDeskError && (e.data as any)?.httpStatus === 429) {
    scheduleRetry((e.data as any).retryAfterSeconds);
  }
}
```
