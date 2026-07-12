package dev.jdesk.platform.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;

import dev.jdesk.api.MenuItem;
import dev.jdesk.api.MenuSpec;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Builds GTK menus ({@code GtkMenu}/{@code GtkMenuItem}) from a {@link MenuSpec} for tray and
 * context menus, dispatching each item's {@code activate} signal to its listener by item
 * pointer. Compile-verified only (no Linux environment here); the GTK signal wiring is
 * runtime-verified on the Linux CI lane.
 */
final class LinuxMenu {
    private static final Logger LOG = System.getLogger(LinuxMenu.class.getName());

    private record Binding(String id, Consumer<String> handler) {
    }

    private static final Map<Long, Binding> BINDINGS = new ConcurrentHashMap<>();
    private static final MemorySegment ACTIVATE_STUB;
    private static final MemorySegment SELECTION_DONE_STUB;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ACTIVATE_STUB = Gtk.upcall(lookup.findStatic(LinuxMenu.class, "onActivate",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            SELECTION_DONE_STUB = Gtk.upcall(lookup.findStatic(LinuxMenu.class, "onSelectionDone",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private LinuxMenu() {
    }

    /**
     * Pops up a context menu and, via a nested {@code gtk_main} loop, waits for the user's
     * choice (GTK popups are non-modal, so a nested loop is the idiomatic way to return a
     * selection like {@code gtk_dialog_run} does).
     */
    static java.util.Optional<String> showContextMenu(MenuSpec spec) {
        java.util.concurrent.atomic.AtomicReference<String> selected =
                new java.util.concurrent.atomic.AtomicReference<>();
        MemorySegment menu = build(spec.items(), selected::set);
        try {
            Gtk.G_OBJECT_REF_SINK.invokeExact(menu);
            Gtk.signalConnect(menu, "selection-done", SELECTION_DONE_STUB);
            Gtk.GTK_MENU_POPUP_AT_POINTER.invokeExact(menu, MemorySegment.NULL);
            Gtk.GTK_MAIN.invokeExact(); // nested loop; quit on selection-done
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        } finally {
            Gtk.gObjectUnref(menu);
        }
        return java.util.Optional.ofNullable(selected.get());
    }

    @SuppressWarnings("unused") // GTK "selection-done" callback
    static void onSelectionDone(MemorySegment menu, MemorySegment userData) {
        try {
            Gtk.GTK_MAIN_QUIT.invokeExact();
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "GTK menu selection-done failed", t);
        }
    }

    /** Builds a realized {@code GtkMenu*} from {@code items}, routing activations to {@code onAction}. */
    static MemorySegment build(List<MenuItem> items, Consumer<String> onAction) {
        try {
            MemorySegment menu = (MemorySegment) Gtk.GTK_MENU_NEW.invokeExact();
            for (MenuItem item : items) {
                MemorySegment widget = switch (item) {
                    case MenuItem.Separator ignored ->
                            (MemorySegment) Gtk.GTK_SEPARATOR_MENU_ITEM_NEW.invokeExact();
                    case MenuItem.Submenu submenu -> {
                        MemorySegment parent = labelItem(submenu.label());
                        Gtk.GTK_MENU_ITEM_SET_SUBMENU.invokeExact(parent,
                                build(submenu.items(), onAction));
                        yield parent;
                    }
                    case MenuItem.Action action -> {
                        MemorySegment mi = labelItem(action.label());
                        BINDINGS.put(mi.address(), new Binding(action.id(), onAction));
                        Gtk.signalConnect(mi, "activate", ACTIVATE_STUB);
                        yield mi;
                    }
                };
                Gtk.GTK_MENU_SHELL_APPEND.invokeExact(menu, widget);
            }
            Gtk.GTK_WIDGET_SHOW_ALL2.invokeExact(menu);
            return menu;
        } catch (Throwable t) {
            throw Gtk.rethrow(t);
        }
    }

    private static MemorySegment labelItem(String label) throws Throwable {
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            return (MemorySegment) Gtk.GTK_MENU_ITEM_NEW_WITH_LABEL.invokeExact(
                    arena.allocateFrom(label));
        }
    }

    @SuppressWarnings("unused") // GTK "activate" callback
    static void onActivate(MemorySegment item, MemorySegment userData) {
        try {
            Binding binding = BINDINGS.get(item.address());
            if (binding != null && binding.handler() != null) {
                binding.handler().accept(binding.id());
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "GTK menu activate dispatch failed", t);
        }
    }
}
