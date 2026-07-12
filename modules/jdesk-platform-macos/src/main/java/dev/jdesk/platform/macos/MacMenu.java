package dev.jdesk.platform.macos;

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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Builds and installs the macOS application menu ({@code NSMenu}/{@code NSMenuItem}). Actions
 * target a single process-lifetime {@code JDeskMenuTarget} whose {@code jdeskMenuAction:}
 * looks the clicked item's tag back to its action id and hands it to the current listener on
 * the UI thread. Only one main menu is active at a time, so tags are per-install.
 *
 * <p>Install is structurally self-checked (the menu really becomes {@code NSApp.mainMenu}
 * with the expected arity); the click→listener path itself cannot be exercised without a
 * real menu selection and is verified by hand.
 */
final class MacMenu {
    private static final Logger LOG = System.getLogger(MacMenu.class.getName());

    private static final int FLAG_SHIFT = 0x20000;
    private static final int FLAG_CONTROL = 0x40000;
    private static final int FLAG_OPTION = 0x80000;
    private static final int FLAG_COMMAND = 0x100000;

    /** Each clickable item's tag maps to (action id, listener). Tags are globally unique. */
    private record Binding(String id, Consumer<String> handler) {
    }

    private static final Map<Integer, Binding> BINDINGS = new ConcurrentHashMap<>();
    private static final AtomicInteger TAG_SEQ = new AtomicInteger(1);
    private static volatile MemorySegment sharedTarget;

    private MacMenu() {
    }

    static synchronized void install(MemorySegment nsApp, MenuSpec spec, Consumer<String> onAction) {
        ensureTarget();
        MemorySegment pool = ObjC.autoreleasePoolPush();
        try {
            MemorySegment menu = buildMenu(spec.items(), onAction);
            ObjC.sendVoid(nsApp, "setMainMenu:", menu);
        } finally {
            ObjC.autoreleasePoolPop(pool);
        }
        // Structural self-check: the menu is really installed with the expected arity.
        MemorySegment installed = ObjC.send(nsApp, "mainMenu");
        if (installed.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("setMainMenu: installed no menu");
        }
        long count = ObjC.sendLong(installed, "numberOfItems");
        if (count != spec.items().size()) {
            throw new IllegalStateException("menu arity mismatch: expected "
                    + spec.items().size() + " but installed " + count);
        }
    }

    /** Builds a standalone {@code NSMenu} (e.g. a tray menu) with its own action listener. */
    static synchronized MemorySegment buildStandaloneMenu(MenuSpec spec, Consumer<String> onAction) {
        ensureTarget();
        return buildMenu(spec.items(), onAction);
    }

    private static MemorySegment buildMenu(List<MenuItem> items, Consumer<String> onAction) {
        MemorySegment menu = ObjC.send(ObjC.send(ObjC.cls("NSMenu"), "alloc"), "init");
        ObjC.autorelease(menu);
        for (MenuItem item : items) {
            MemorySegment menuItem = switch (item) {
                case MenuItem.Separator ignored ->
                        ObjC.send(ObjC.cls("NSMenuItem"), "separatorItem");
                case MenuItem.Submenu submenu -> {
                    MemorySegment parent = newItem(submenu.label(), "", MemorySegment.NULL);
                    ObjC.sendVoid(parent, "setSubmenu:", buildMenu(submenu.items(), onAction));
                    yield parent;
                }
                case MenuItem.Action action -> {
                    String key = action.accelerator().map(MacMenu::keyEquivalent).orElse("");
                    MemorySegment mi = newItem(action.label(), key, ObjC.sel("jdeskMenuAction:"));
                    ObjC.sendVoid(mi, "setTarget:", sharedTarget);
                    int tag = TAG_SEQ.getAndIncrement();
                    BINDINGS.put(tag, new Binding(action.id(), onAction));
                    ObjC.sendVoidLong(mi, "setTag:", tag);
                    action.accelerator().ifPresent(acc ->
                            ObjC.sendVoidLong(mi, "setKeyEquivalentModifierMask:", modifierMask(acc)));
                    yield mi;
                }
            };
            ObjC.sendVoid(menu, "addItem:", menuItem);
        }
        return menu;
    }

    private static MemorySegment newItem(String title, String keyEquiv, MemorySegment actionSel) {
        MemorySegment allocated = ObjC.send(ObjC.cls("NSMenuItem"), "alloc");
        MemorySegment item;
        try {
            item = (MemorySegment) ObjC.msgSend(FunctionDescriptor.of(
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)).invokeExact(
                    allocated, ObjC.sel("initWithTitle:action:keyEquivalent:"),
                    ObjC.nsString(title), actionSel, ObjC.nsString(keyEquiv));
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
        ObjC.autorelease(item);
        return item;
    }

    private static synchronized void ensureTarget() {
        if (sharedTarget != null) {
            return;
        }
        MemorySegment cls;
        try {
            cls = new ObjCClassBuilder("JDeskMenuTarget")
                    .method("jdeskMenuAction:", "v@:@",
                            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
                            MethodHandles.lookup().findStatic(MacMenu.class, "impMenuAction",
                                    MethodType.methodType(void.class, MemorySegment.class,
                                            MemorySegment.class, MemorySegment.class)))
                    .register();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        MemorySegment instance = ObjC.send(ObjC.send(cls, "alloc"), "init");
        ObjC.retain(instance); // process-lifetime target; never released
        sharedTarget = instance;
    }

    @SuppressWarnings("unused") // invoked from AppKit via the JDeskMenuTarget IMP
    static void impMenuAction(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try {
            Binding binding = BINDINGS.get((int) ObjC.sendLong(sender, "tag"));
            if (binding != null && binding.handler() != null) {
                binding.handler().accept(binding.id());
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "Menu action dispatch failed", t);
        }
    }

    private static String keyEquivalent(String accelerator) {
        String[] parts = accelerator.split("\\+");
        return parts[parts.length - 1].trim().toLowerCase(Locale.ROOT);
    }

    private static long modifierMask(String accelerator) {
        String[] parts = accelerator.split("\\+");
        int mask = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].trim().toLowerCase(Locale.ROOT)) {
                case "cmd", "command", "cmdorctrl", "meta" -> mask |= FLAG_COMMAND;
                case "ctrl", "control" -> mask |= FLAG_CONTROL;
                case "alt", "option", "opt" -> mask |= FLAG_OPTION;
                case "shift" -> mask |= FLAG_SHIFT;
                default -> { }
            }
        }
        return mask;
    }
}
