package dev.jdesk.platform.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Minimal Keychain Services + CoreFoundation bindings for generic-password items
 * (SecItemAdd/CopyMatching/Update/Delete). Only public, documented APIs.
 */
final class Keychain {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup SECURITY = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/Security.framework/Security", Arena.global());
    private static final SymbolLookup CORE_FOUNDATION = SymbolLookup.libraryLookup(
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global());

    static final int ERR_SEC_SUCCESS = 0;
    static final int ERR_SEC_DUPLICATE_ITEM = -25299;
    static final int ERR_SEC_ITEM_NOT_FOUND = -25300;
    private static final int K_CF_STRING_ENCODING_UTF8 = 0x08000100;

    static final MethodHandle SEC_ITEM_ADD = down(SECURITY, "SecItemAdd",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle SEC_ITEM_COPY_MATCHING = down(SECURITY, "SecItemCopyMatching",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle SEC_ITEM_UPDATE = down(SECURITY, "SecItemUpdate",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle SEC_ITEM_DELETE = down(SECURITY, "SecItemDelete",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle CF_DICTIONARY_CREATE = down(CORE_FOUNDATION,
            "CFDictionaryCreate", FunctionDescriptor.of(ADDRESS,
                    ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle CF_STRING_CREATE = down(CORE_FOUNDATION,
            "CFStringCreateWithBytes", FunctionDescriptor.of(ADDRESS,
                    ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_BYTE));
    private static final MethodHandle CF_DATA_CREATE = down(CORE_FOUNDATION,
            "CFDataCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    private static final MethodHandle CF_DATA_GET_LENGTH = down(CORE_FOUNDATION,
            "CFDataGetLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle CF_DATA_GET_BYTE_PTR = down(CORE_FOUNDATION,
            "CFDataGetBytePtr", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CF_RELEASE = down(CORE_FOUNDATION,
            "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));

    // Global CFStringRef constants: the symbol is the variable's address; dereference.
    static final MemorySegment K_SEC_CLASS = constant(SECURITY, "kSecClass");
    static final MemorySegment K_SEC_CLASS_GENERIC_PASSWORD =
            constant(SECURITY, "kSecClassGenericPassword");
    static final MemorySegment K_SEC_ATTR_SERVICE = constant(SECURITY, "kSecAttrService");
    static final MemorySegment K_SEC_ATTR_ACCOUNT = constant(SECURITY, "kSecAttrAccount");
    static final MemorySegment K_SEC_VALUE_DATA = constant(SECURITY, "kSecValueData");
    static final MemorySegment K_SEC_RETURN_DATA = constant(SECURITY, "kSecReturnData");
    static final MemorySegment K_SEC_MATCH_LIMIT = constant(SECURITY, "kSecMatchLimit");
    static final MemorySegment K_SEC_MATCH_LIMIT_ONE = constant(SECURITY, "kSecMatchLimitOne");
    static final MemorySegment K_CF_BOOLEAN_TRUE = constant(CORE_FOUNDATION, "kCFBooleanTrue");
    // Callback structs are passed by address (no dereference).
    private static final MemorySegment TYPE_KEY_CALLBACKS =
            symbol(CORE_FOUNDATION, "kCFTypeDictionaryKeyCallBacks");
    private static final MemorySegment TYPE_VALUE_CALLBACKS =
            symbol(CORE_FOUNDATION, "kCFTypeDictionaryValueCallBacks");

    private Keychain() {
    }

    private static MethodHandle down(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(
                () -> new IllegalStateException("Missing symbol: " + name)), desc);
    }

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(
                () -> new IllegalStateException("Missing symbol: " + name));
    }

    private static MemorySegment constant(SymbolLookup lookup, String name) {
        return symbol(lookup, name)
                .reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ADDRESS, 0);
    }

    /** New CFString (+1); caller releases. */
    static MemorySegment cfString(Arena arena, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        MemorySegment buffer = arena.allocate(Math.max(bytes.length, 1));
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buffer, 0, bytes.length);
        try {
            return (MemorySegment) CF_STRING_CREATE.invokeExact(MemorySegment.NULL,
                    buffer, (long) bytes.length, K_CF_STRING_ENCODING_UTF8, (byte) 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** New CFData (+1); caller releases. */
    static MemorySegment cfData(Arena arena, byte[] bytes) {
        MemorySegment buffer = arena.allocate(Math.max(bytes.length, 1));
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, buffer, 0, bytes.length);
        try {
            return (MemorySegment) CF_DATA_CREATE.invokeExact(
                    MemorySegment.NULL, buffer, (long) bytes.length);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** New CFDictionary (+1) with CFType callbacks; caller releases. */
    static MemorySegment dictionary(Arena arena, MemorySegment[] keys, MemorySegment[] values) {
        MemorySegment keyArray = arena.allocate(ADDRESS, keys.length);
        MemorySegment valueArray = arena.allocate(ADDRESS, values.length);
        for (int i = 0; i < keys.length; i++) {
            keyArray.setAtIndex(ADDRESS, i, keys[i]);
            valueArray.setAtIndex(ADDRESS, i, values[i]);
        }
        try {
            return (MemorySegment) CF_DICTIONARY_CREATE.invokeExact(MemorySegment.NULL,
                    keyArray, valueArray, (long) keys.length,
                    TYPE_KEY_CALLBACKS, TYPE_VALUE_CALLBACKS);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static byte[] dataBytes(MemorySegment cfData) {
        try {
            long length = (long) CF_DATA_GET_LENGTH.invokeExact(cfData);
            MemorySegment pointer = (MemorySegment) CF_DATA_GET_BYTE_PTR.invokeExact(cfData);
            byte[] bytes = new byte[(int) length];
            MemorySegment.copy(pointer.reinterpret(length), 0,
                    MemorySegment.ofArray(bytes), 0, length);
            return bytes;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void release(MemorySegment cfObject) {
        if (cfObject == null || cfObject.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            CF_RELEASE.invokeExact(cfObject);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException runtime) {
            return runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Keychain call failed", t);
    }
}
