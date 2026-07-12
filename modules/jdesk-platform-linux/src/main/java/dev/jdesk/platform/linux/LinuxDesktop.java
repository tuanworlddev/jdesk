package dev.jdesk.platform.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.SystemTheme;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Linux desktop-integration primitives (GAP-004) via GTK / libnotify.
 *
 * <p>Honest status: compile-verified only — no Linux environment on the authoring machine.
 * Runtime verification belongs to the Linux native CI lane. Theme detection uses the GTK
 * theme name (a heuristic that covers the common {@code *-dark} themes); a desktop-portal
 * {@code color-scheme} query would be more precise on GNOME.
 */
final class LinuxDesktop {
    private static final Arena ARENA = Arena.ofShared();
    private static final SymbolLookup NOTIFY =
            SymbolLookup.libraryLookup("libnotify.so.4", ARENA);

    private static final MethodHandle NOTIFY_INIT = Gtk.LINKER.downcallHandle(
            NOTIFY.findOrThrow("notify_init"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle NOTIFY_NEW = Gtk.LINKER.downcallHandle(
            NOTIFY.findOrThrow("notify_notification_new"),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle NOTIFY_SHOW = Gtk.LINKER.downcallHandle(
            NOTIFY.findOrThrow("notify_notification_show"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static volatile boolean notifyInitialized;

    private LinuxDesktop() {
    }

    /** DARK when the active GTK theme name contains "dark", else LIGHT. */
    static SystemTheme systemTheme() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment settings = (MemorySegment) Gtk.GTK_SETTINGS_GET_DEFAULT.invokeExact();
            if (settings.equals(MemorySegment.NULL)) {
                return SystemTheme.LIGHT;
            }
            MemorySegment out = arena.allocate(ADDRESS);
            Gtk.G_OBJECT_GET.invokeExact(settings, arena.allocateFrom("gtk-theme-name"),
                    out, MemorySegment.NULL);
            MemorySegment namePtr = out.get(ADDRESS, 0);
            String name = Gtk.javaString(namePtr);
            if (!namePtr.equals(MemorySegment.NULL)) {
                Gtk.G_FREE.invokeExact(namePtr);
            }
            return name != null && name.toLowerCase(Locale.ROOT).contains("dark")
                    ? SystemTheme.DARK : SystemTheme.LIGHT;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Could not read GTK theme", null, t);
        }
    }

    /** Posts a notification through libnotify (org.freedesktop.Notifications). */
    static void showNotification(String title, String body) {
        try (Arena arena = Arena.ofConfined()) {
            ensureInit(arena);
            MemorySegment notification = (MemorySegment) NOTIFY_NEW.invokeExact(
                    arena.allocateFrom(title == null ? "" : title),
                    arena.allocateFrom(body == null ? "" : body), MemorySegment.NULL);
            if (notification.equals(MemorySegment.NULL)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "notify_notification_new failed");
            }
            try {
                int unused = (int) NOTIFY_SHOW.invokeExact(notification, MemorySegment.NULL);
            } finally {
                Gtk.G_OBJECT_UNREF.invokeExact(notification);
            }
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Notification failed", null, t);
        }
    }

    private static void ensureInit(Arena arena) throws Throwable {
        if (!notifyInitialized) {
            synchronized (LinuxDesktop.class) {
                if (!notifyInitialized) {
                    int unused = (int) NOTIFY_INIT.invokeExact(arena.allocateFrom("JDesk"));
                    notifyInitialized = true;
                }
            }
        }
    }
}
