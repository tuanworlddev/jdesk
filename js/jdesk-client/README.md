# jdesk-client

Static TypeScript runtime for the JDesk IPC protocol v1
(`docs/architecture/ipc-protocol.md`): nonce lifecycle, lazy `hello` handshake,
`invoke` with unique ids and a 1 MiB client-side size limit, per-call timeout and
`AbortSignal` cancellation (both send a `cancel` envelope), navigation-reset handling
(in-flight calls reject with `NAVIGATION_RESET`, the handshake is redone lazily), and an
event subscription API (`on(event, handler)` returns an unsubscribe function).

Zero dependencies, ES2020 modules. Build with `npm run build` (plain `tsc`); building is
not required for code generation — `jdesk-codegen` emits `types.ts`/`commands.ts` that
import `invoke` from this package.

```ts
import { commands } from "./jdesk-ts/commands";

const response = await commands.greeting.greet({ name: "Tuan" });
```
