# Store secrets in the OS credential store

API keys and tokens do not belong in plaintext JSON under the user's home directory.
`SecretStore` puts them where the operating system keeps credentials:

| Platform | Backend | Where it lives |
| --- | --- | --- |
| macOS | Keychain Services (`SecItemAdd/CopyMatching/Update/Delete`) | login keychain, service `jdesk:<appId>` |
| Windows | DPAPI (`CryptProtectData`, user-scoped) | ciphertext blobs in `~/.jdesk/secrets/<appId>.properties` — only the same Windows user on the same machine can decrypt |
| Linux | Secret Service via `secret-tool` (libsecret) | the desktop keyring (GNOME Keyring / KWallet) |

There is deliberately **no plaintext fallback**: on Linux without `secret-tool`
installed, calls fail with a clear error instead of silently writing a readable file.

## Usage

```java
@DesktopCommand("shop.saveApiKey")
@RequiresCapability("shop:configure")
public CompletionStage<Void> saveApiKey(SaveKeyRequest request, InvocationContext context) {
    context.application().secrets().put("wb-api-key", request.apiKey());
    return CompletableFuture.completedFuture(null);
}

Optional<String> key = context.application().secrets().get("wb-api-key");
context.application().secrets().delete("wb-api-key");
```

Keys are 1..128 characters, values up to 64 KiB, namespaced per application id. Calls
may block on the OS credential service — fine on command-handler virtual threads; do
not call on the UI thread. Never expose a generic get/put secret command straight to
the frontend; keep secrets server-side and expose only the operations that need them.

Platform status: macOS verified live against the real Keychain (native-smoke case
`java:secret-store`: store, read-back, rotate, delete). Windows and Linux backends are
implemented and compile-verified; they carry the same tests once CI/hardware runs them.
