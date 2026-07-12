package dev.jdesk.platform.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.SystemTheme;
import dev.jdesk.api.TraySpec;
import dev.jdesk.webview.spi.TrayControl;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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

    /** Sets the default icon for all windows from PNG bytes (GdkPixbuf). */
    static void setApplicationIcon(byte[] pngData) {
        MemorySegment pixbuf = pixbufFromPng(pngData);
        if (pixbuf.equals(MemorySegment.NULL)) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Could not decode PNG icon");
        }
        try {
            Gtk.GTK_WINDOW_SET_DEFAULT_ICON.invokeExact(pixbuf); // takes its own ref
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Could not set icon", null, t);
        } finally {
            Gtk.gObjectUnref(pixbuf);
        }
    }

    /** Linux has no dock badge; the taskbar/launcher shows nothing here. Documented no-op. */
    static void setDockBadge(String label) {
        // No cross-desktop dock-badge API (Unity LauncherEntry is DBus-only and deprecated).
    }

    // ---- tray (GtkStatusIcon) ----
    private static final Map<Long, MemorySegment> TRAY_MENUS = new ConcurrentHashMap<>();
    private static final MemorySegment POPUP_STUB;

    static {
        try {
            POPUP_STUB = Gtk.upcall(MethodHandles.lookup().findStatic(LinuxDesktop.class,
                    "onPopupMenu", MethodType.methodType(void.class, MemorySegment.class,
                            int.class, int.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static TrayControl createTrayItem(TraySpec spec, Consumer<String> onAction) {
        try {
            MemorySegment statusIcon = (MemorySegment) Gtk.GTK_STATUS_ICON_NEW.invokeExact();
            if (statusIcon.equals(MemorySegment.NULL)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "gtk_status_icon_new failed");
            }
            if (spec.iconPng().isPresent()) {
                MemorySegment pixbuf = pixbufFromPng(spec.iconPng().get());
                if (!pixbuf.equals(MemorySegment.NULL)) {
                    Gtk.GTK_STATUS_ICON_SET_FROM_PIXBUF.invokeExact(statusIcon, pixbuf);
                    Gtk.gObjectUnref(pixbuf);
                }
            }
            setStatusTitle(statusIcon, spec.title());
            MemorySegment menu = LinuxMenu.build(spec.menu().items(), onAction);
            Gtk.G_OBJECT_REF_SINK.invokeExact(menu); // own the floating menu
            TRAY_MENUS.put(statusIcon.address(), menu);
            Gtk.signalConnect(statusIcon, "popup-menu", POPUP_STUB);
            Gtk.GTK_STATUS_ICON_SET_VISIBLE.invokeExact(statusIcon, 1);
            return new TrayControl() {
                private volatile boolean removed;

                @Override public void setTitle(String title) {
                    if (!removed) {
                        try {
                            setStatusTitle(statusIcon, title);
                        } catch (Throwable t) {
                            throw Gtk.rethrow(t);
                        }
                    }
                }

                @Override public synchronized void remove() {
                    if (removed) {
                        return;
                    }
                    removed = true;
                    TRAY_MENUS.remove(statusIcon.address());
                    try {
                        Gtk.GTK_STATUS_ICON_SET_VISIBLE.invokeExact(statusIcon, 0);
                    } catch (Throwable ignored) {
                        // best-effort
                    }
                    Gtk.gObjectUnref(statusIcon);
                    Gtk.gObjectUnref(menu);
                }
            };
        } catch (JDeskException e) {
            throw e;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Tray item failed", null, t);
        }
    }

    private static void setStatusTitle(MemorySegment statusIcon, String title) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom(title == null ? "" : title);
            Gtk.GTK_STATUS_ICON_SET_TITLE.invokeExact(statusIcon, text);
            Gtk.GTK_STATUS_ICON_SET_TOOLTIP_TEXT.invokeExact(statusIcon, text);
        }
    }

    @SuppressWarnings("unused") // GTK "popup-menu" callback
    static void onPopupMenu(MemorySegment statusIcon, int button, int activateTime,
            MemorySegment userData) {
        MemorySegment menu = TRAY_MENUS.get(statusIcon.address());
        if (menu != null) {
            try {
                Gtk.GTK_MENU_POPUP_AT_POINTER.invokeExact(menu, MemorySegment.NULL);
            } catch (Throwable t) {
                // best-effort; runtime path is CI-verified
            }
        }
    }

    static MemorySegment pixbufFromPng(byte[] png) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(Math.max(1, png.length));
            if (png.length > 0) {
                MemorySegment.copy(png, 0, buffer, JAVA_BYTE, 0, png.length);
            }
            MemorySegment bytes = (MemorySegment) Gtk.G_BYTES_NEW.invokeExact(buffer, (long) png.length);
            MemorySegment stream =
                    (MemorySegment) Gtk.G_MEMORY_INPUT_STREAM_NEW_FROM_BYTES.invokeExact(bytes);
            MemorySegment pixbuf = (MemorySegment) Gtk.GDK_PIXBUF_NEW_FROM_STREAM.invokeExact(
                    stream, MemorySegment.NULL, MemorySegment.NULL);
            Gtk.gObjectUnref(stream);
            Gtk.G_BYTES_UNREF.invokeExact(bytes);
            return pixbuf;
        } catch (Throwable t) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "PNG decode failed", null, t);
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
