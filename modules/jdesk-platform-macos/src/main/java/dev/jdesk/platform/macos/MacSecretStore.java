package dev.jdesk.platform.macos;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.SecretStore;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * {@link SecretStore} backed by macOS Keychain Services generic-password items:
 * service = {@code jdesk:<applicationId>}, account = the secret key. Callable from any
 * thread. Values live in the user's login keychain — never on disk in plaintext.
 */
final class MacSecretStore implements SecretStore {
    private final String service;

    MacSecretStore(String applicationId) {
        this.service = "jdesk:" + applicationId;
    }

    @Override
    public Optional<String> get(String key) {
        validateKey(key);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment serviceRef = Keychain.cfString(arena, service);
            MemorySegment accountRef = Keychain.cfString(arena, key);
            MemorySegment query = Keychain.dictionary(arena,
                    new MemorySegment[] {Keychain.K_SEC_CLASS, Keychain.K_SEC_ATTR_SERVICE,
                            Keychain.K_SEC_ATTR_ACCOUNT, Keychain.K_SEC_RETURN_DATA,
                            Keychain.K_SEC_MATCH_LIMIT},
                    new MemorySegment[] {Keychain.K_SEC_CLASS_GENERIC_PASSWORD, serviceRef,
                            accountRef, Keychain.K_CF_BOOLEAN_TRUE,
                            Keychain.K_SEC_MATCH_LIMIT_ONE});
            try {
                MemorySegment out = arena.allocate(ADDRESS);
                int status = (int) Keychain.SEC_ITEM_COPY_MATCHING.invokeExact(query, out);
                if (status == Keychain.ERR_SEC_ITEM_NOT_FOUND) {
                    return Optional.empty();
                }
                check(status, "SecItemCopyMatching");
                MemorySegment data = out.get(ADDRESS, 0);
                try {
                    return Optional.of(new String(Keychain.dataBytes(data),
                            StandardCharsets.UTF_8));
                } finally {
                    Keychain.release(data);
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw Keychain.rethrow(t);
            } finally {
                Keychain.release(query);
                Keychain.release(serviceRef);
                Keychain.release(accountRef);
            }
        }
    }

    @Override
    public void put(String key, String value) {
        validateKey(key);
        if (value == null) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Secret value must not be null");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 64 * 1024) {
            throw new JDeskException(ErrorCode.PAYLOAD_TOO_LARGE, "Secret value exceeds 64 KiB");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment serviceRef = Keychain.cfString(arena, service);
            MemorySegment accountRef = Keychain.cfString(arena, key);
            MemorySegment dataRef = Keychain.cfData(arena, bytes);
            MemorySegment add = Keychain.dictionary(arena,
                    new MemorySegment[] {Keychain.K_SEC_CLASS, Keychain.K_SEC_ATTR_SERVICE,
                            Keychain.K_SEC_ATTR_ACCOUNT, Keychain.K_SEC_VALUE_DATA},
                    new MemorySegment[] {Keychain.K_SEC_CLASS_GENERIC_PASSWORD, serviceRef,
                            accountRef, dataRef});
            MemorySegment query = MemorySegment.NULL;
            MemorySegment update = MemorySegment.NULL;
            try {
                int status = (int) Keychain.SEC_ITEM_ADD.invokeExact(add, MemorySegment.NULL);
                if (status == Keychain.ERR_SEC_DUPLICATE_ITEM) {
                    query = Keychain.dictionary(arena,
                            new MemorySegment[] {Keychain.K_SEC_CLASS,
                                    Keychain.K_SEC_ATTR_SERVICE, Keychain.K_SEC_ATTR_ACCOUNT},
                            new MemorySegment[] {Keychain.K_SEC_CLASS_GENERIC_PASSWORD,
                                    serviceRef, accountRef});
                    update = Keychain.dictionary(arena,
                            new MemorySegment[] {Keychain.K_SEC_VALUE_DATA},
                            new MemorySegment[] {dataRef});
                    status = (int) Keychain.SEC_ITEM_UPDATE.invokeExact(query, update);
                }
                check(status, "SecItemAdd/Update");
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw Keychain.rethrow(t);
            } finally {
                Keychain.release(update);
                Keychain.release(query);
                Keychain.release(add);
                Keychain.release(dataRef);
                Keychain.release(serviceRef);
                Keychain.release(accountRef);
            }
        }
    }

    @Override
    public void delete(String key) {
        validateKey(key);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment serviceRef = Keychain.cfString(arena, service);
            MemorySegment accountRef = Keychain.cfString(arena, key);
            MemorySegment query = Keychain.dictionary(arena,
                    new MemorySegment[] {Keychain.K_SEC_CLASS, Keychain.K_SEC_ATTR_SERVICE,
                            Keychain.K_SEC_ATTR_ACCOUNT},
                    new MemorySegment[] {Keychain.K_SEC_CLASS_GENERIC_PASSWORD, serviceRef,
                            accountRef});
            try {
                int status = (int) Keychain.SEC_ITEM_DELETE.invokeExact(query);
                if (status != Keychain.ERR_SEC_ITEM_NOT_FOUND) {
                    check(status, "SecItemDelete");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw Keychain.rethrow(t);
            } finally {
                Keychain.release(query);
                Keychain.release(serviceRef);
                Keychain.release(accountRef);
            }
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Secret key must be 1..128 non-blank characters");
        }
    }

    private static void check(int status, String operation) {
        if (status != Keychain.ERR_SEC_SUCCESS) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                    "Keychain operation failed (" + operation + ", OSStatus " + status + ")");
        }
    }
}
