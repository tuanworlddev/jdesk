package dev.jdesk.api;

import java.util.Optional;

/**
 * OS-backed secret storage for API keys, tokens, and credentials — never plaintext
 * config files. Backends: macOS Keychain Services, Windows DPAPI (user-scoped
 * encryption), Linux Secret Service (libsecret via {@code secret-tool}). Secrets are
 * namespaced per application id; keys are limited to 128 chars, values to 64 KiB.
 *
 * <p>Obtain via {@link ApplicationHandle#secrets()} (e.g. from
 * {@code InvocationContext.application().secrets()} inside a command handler). Calls
 * may block on the OS credential service — fine on command-handler virtual threads,
 * do not call on the UI thread.
 */
public interface SecretStore {

    /** @return the stored secret, or empty when the key was never stored. */
    Optional<String> get(String key);

    /** Stores or replaces one secret. */
    void put(String key, String value);

    /** Removes one secret; removing an absent key is a no-op. */
    void delete(String key);
}
