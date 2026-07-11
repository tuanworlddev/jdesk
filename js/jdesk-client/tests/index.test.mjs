import assert from "node:assert/strict";
import test from "node:test";

class TestDocument extends EventTarget {
  message(detail) {
    const event = new Event("jdesk-message");
    Object.defineProperty(event, "detail", { value: JSON.stringify(detail) });
    this.dispatchEvent(event);
  }
}

async function loadClient(respond) {
  const document = new TestDocument();
  globalThis.document = document;
  globalThis.window = {
    __jdesk: {
      nonce: "nonce-1",
      post(json) {
        respond(JSON.parse(json), document);
      },
    },
  };
  return import(`../dist/index.js?test=${Math.random()}`);
}

test("performs handshake and correlates an invocation result", async () => {
  const client = await loadClient((message, document) => {
    queueMicrotask(() => {
      if (message.kind === "hello") {
        document.message({ v: 1, kind: "helloAck", ok: true, nonce: message.nonce });
      } else if (message.kind === "invoke") {
        document.message({ v: 1, kind: "result", id: message.id, ok: true,
          value: { echoed: message.payload.text } });
      }
    });
  });

  assert.deepEqual(await client.invoke("test.echo", { text: "hello" }), { echoed: "hello" });
});

test("surfaces an explicit protocol-version handshake error", async () => {
  const client = await loadClient((message, document) => {
    if (message.kind === "hello") {
      queueMicrotask(() => document.message({
        v: 1,
        kind: "helloAck",
        ok: false,
        error: { code: "PROTOCOL_VERSION_UNSUPPORTED", message: "Unsupported protocol version 2" },
      }));
    }
  });

  await assert.rejects(client.invoke("test.echo", {}),
    error => error.code === "PROTOCOL_VERSION_UNSUPPORTED");
});

test("rejects invalid client timeouts before posting invoke", async () => {
  const client = await loadClient((message, document) => {
    if (message.kind === "hello") {
      queueMicrotask(() => document.message({ v: 1, kind: "helloAck", ok: true,
        nonce: message.nonce }));
    }
  });

  await assert.rejects(client.invoke("test.echo", {}, { timeoutMs: Number.NaN }),
    error => error.code === "INVALID_REQUEST");
});
