# Stream binary data to the frontend

Command results are single JSON envelopes capped at 1 MiB, which is the wrong shape for
file contents, exports, or anything measured in megabytes. For those, return a
`BinaryStream` from a command handler and consume it in the page with
`invokeStream(...)` — a pull-based protocol with real backpressure.

Verified live on macOS (run `1783786141-cbdefa21a3dd2e5c`): 2 GiB streamed through the
real WKWebView bridge in 8192 pulls at ~150 MiB/s with zero corruption, and cancellation
closes the stream token immediately.

## Java side: return a BinaryStream

```java
new CommandDefinition("media.export", Optional.of("media:read"),
        ExportRequest.class, Optional.of(Duration.ofMinutes(10)),
        (request, context) -> {
            Path file = resolveExport((ExportRequest) request);
            try {
                return CompletableFuture.completedFuture(
                        BinaryStream.of(file, "application/zip"));
            } catch (IOException e) {
                throw new JDeskException(ErrorCode.INTERNAL, "Export unavailable");
            }
        });
```

The handler completes immediately with a descriptor; the runtime registers the stream and
opens the `InputStream` lazily on the first pull. Streams are scoped to the current
navigation — a reload or crash recovery closes every open stream.

## JS side: consume a ReadableStream

```ts
import { invokeStream } from "jdesk-client";

const result = await invokeStream("media.export", { id: 42 });
// result.length, result.contentType, result.fileName
const reader = result.stream.getReader();
for (;;) {
  const { done, value } = await reader.read();   // one pull per demand
  if (done) break;
  consume(value);                                 // Uint8Array
}
```

Each `read()` issues one `jdesk.stream.pull` for up to `chunkBytes` (default and maximum
256 KiB); the runtime never sends data the page has not asked for — that is the
backpressure. Call `result.stream.cancel()` to stop early; the runtime frees the stream
and subsequent pulls fail with `INVALID_REQUEST`.

## When to use what

| Payload | Mechanism |
| --- | --- |
| Structured data < 1 MiB | Plain command result |
| Progress/status pushes | `context.events().emit(...)` (see [Emitting events](emitting-events.md)) |
| Files, exports, blobs of any size | `BinaryStream` + `invokeStream` |
| Large packaged media for `<video>`/`<audio>` | Serve as an asset; the pipeline answers Range requests with 206 (see [Serving assets](serving-assets.md)) |

Chunks travel base64-encoded inside JSON envelopes (~33% inflation); at the measured
~150 MiB/s effective throughput this is rarely the bottleneck for desktop use cases.
