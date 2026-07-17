package dev.jdesk.platform.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;

/** Safe fixed-shape call to WebKit's variadic website-data-manager constructor. */
final class WebsiteDataManager {
    private static final Arena LIBRARY_ARENA = Arena.ofShared();
    private static final SymbolLookup WEBKIT = SymbolLookup.libraryLookup(
            "libwebkit2gtk-4.1.so.0", LIBRARY_ARENA);
    private static final MethodHandle NEW = Linker.nativeLinker().downcallHandle(
            WEBKIT.findOrThrow("webkit_website_data_manager_new"),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
            Linker.Option.firstVariadicArg(1));

    private WebsiteDataManager() {
    }

    static MemorySegment create(Path dataDirectory, Path cacheDirectory) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment manager = (MemorySegment) NEW.invokeExact(
                    arena.allocateFrom("base-data-directory"),
                    arena.allocateFrom(dataDirectory.toString()),
                    arena.allocateFrom("base-cache-directory"),
                    arena.allocateFrom(cacheDirectory.toString()),
                    MemorySegment.NULL);
            if (manager.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("webkit_website_data_manager_new returned NULL");
            }
            return manager;
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }
}
