package dev.jdesk.platform.windows;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.SecretStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * {@link SecretStore} backed by Windows DPAPI ({@code CryptProtectData} /
 * {@code CryptUnprotectData}, user-scoped): ciphertext blobs are stored base64-encoded
 * in {@code ~/.jdesk/secrets/<applicationId>.properties} and can only be decrypted by
 * the same Windows user on the same machine. Callable from any thread.
 */
final class WindowsSecretStore implements SecretStore {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CRYPT32 =
            SymbolLookup.libraryLookup("crypt32.dll", Arena.global());
    private static final SymbolLookup KERNEL32 =
            SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    // DATA_BLOB: DWORD cbData; BYTE* pbData (8-byte aligned on x64).
    private static final MemoryLayout DATA_BLOB = MemoryLayout.structLayout(
            JAVA_INT.withName("cbData"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pbData"));

    private static final MethodHandle CRYPT_PROTECT_DATA = down("CryptProtectData");
    private static final MethodHandle CRYPT_UNPROTECT_DATA = down("CryptUnprotectData");
    private static final MethodHandle LOCAL_FREE = LINKER.downcallHandle(
            KERNEL32.find("LocalFree").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static MethodHandle down(String name) {
        return LINKER.downcallHandle(CRYPT32.find(name).orElseThrow(
                () -> new IllegalStateException("Missing symbol: " + name)),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
                        ADDRESS, JAVA_INT, ADDRESS));
    }

    private final Path file;

    WindowsSecretStore(String applicationId) {
        this.file = Path.of(System.getProperty("user.home"), ".jdesk", "secrets",
                applicationId + ".properties");
    }

    @Override
    public synchronized Optional<String> get(String key) {
        validateKey(key);
        String encoded = read().getProperty(key);
        if (encoded == null) {
            return Optional.empty();
        }
        byte[] plain = dpapi(CRYPT_UNPROTECT_DATA, Base64.getDecoder().decode(encoded),
                "CryptUnprotectData");
        return Optional.of(new String(plain, StandardCharsets.UTF_8));
    }

    @Override
    public synchronized void put(String key, String value) {
        validateKey(key);
        if (value == null) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Secret value must not be null");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 64 * 1024) {
            throw new JDeskException(ErrorCode.PAYLOAD_TOO_LARGE, "Secret value exceeds 64 KiB");
        }
        byte[] cipher = dpapi(CRYPT_PROTECT_DATA, bytes, "CryptProtectData");
        Properties properties = read();
        properties.setProperty(key, Base64.getEncoder().encodeToString(cipher));
        write(properties);
    }

    @Override
    public synchronized void delete(String key) {
        validateKey(key);
        Properties properties = read();
        if (properties.remove(key) != null) {
            write(properties);
        }
    }

    private static byte[] dpapi(MethodHandle operation, byte[] input, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inBytes = arena.allocate(Math.max(input.length, 1));
            MemorySegment.copy(MemorySegment.ofArray(input), 0, inBytes, 0, input.length);
            MemorySegment in = arena.allocate(DATA_BLOB);
            in.set(JAVA_INT, 0, input.length);
            in.set(ADDRESS, 8, inBytes);
            MemorySegment out = arena.allocate(DATA_BLOB);
            int ok = (int) operation.invokeExact(in, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL, CRYPTPROTECT_UI_FORBIDDEN, out);
            if (ok == 0) {
                throw new JDeskException(ErrorCode.INTERNAL_ERROR, name + " failed");
            }
            int length = out.get(JAVA_INT, 0);
            MemorySegment data = out.get(ADDRESS, 8).reinterpret(length);
            byte[] result = new byte[length];
            MemorySegment.copy(data, 0, MemorySegment.ofArray(result), 0, length);
            MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(out.get(ADDRESS, 8));
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR, name + " failed", t);
        }
    }

    private Properties read() {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                throw new JDeskException(ErrorCode.INTERNAL_ERROR, "Secret store unreadable", e);
            }
        }
        return properties;
    }

    private void write(Properties properties) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "JDesk secrets (DPAPI-encrypted, user-scoped)");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR, "Secret store not writable", e);
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Secret key must be 1..128 non-blank characters");
        }
    }
}
