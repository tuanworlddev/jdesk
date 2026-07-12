package dev.jdesk.platform.macos;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.TraySpec;
import dev.jdesk.webview.spi.TrayControl;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;

/**
 * A macOS status-bar item ({@code NSStatusItem}) with a click menu. The item is retained for
 * its lifetime and released on {@link #remove()}; its menu dispatches through the shared
 * {@link MacMenu} target. Creation is structurally checked (a real, non-nil status item);
 * clicking the item and choosing menu entries is a GUI action, not auto-tested.
 */
final class MacTray implements TrayControl {
    private static final double NS_VARIABLE_STATUS_ITEM_LENGTH = -1.0;

    private final MemorySegment statusItem;
    private volatile boolean removed;

    private MacTray(MemorySegment statusItem) {
        this.statusItem = statusItem;
    }

    static MacTray create(TraySpec spec, Consumer<String> onAction) {
        MemorySegment pool = ObjC.autoreleasePoolPush();
        try {
            MemorySegment bar = ObjC.send(ObjC.cls("NSStatusBar"), "systemStatusBar");
            MemorySegment item;
            try {
                item = (MemorySegment) ObjC.msgSend(FunctionDescriptor.of(
                        ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE)).invokeExact(
                        bar, ObjC.sel("statusItemWithLength:"), NS_VARIABLE_STATUS_ITEM_LENGTH);
            } catch (Throwable t) {
                throw ObjC.rethrow(t);
            }
            if (item.equals(MemorySegment.NULL)) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Could not create status item");
            }
            ObjC.retain(item); // survive past this pool; released in remove()
            MemorySegment button = ObjC.send(item, "button");
            if (!button.equals(MemorySegment.NULL)) {
                ObjC.sendVoid(button, "setTitle:", ObjC.nsString(spec.title()));
                spec.iconPng().ifPresent(png -> setTemplateImage(button, png));
            }
            ObjC.sendVoid(item, "setMenu:", MacMenu.buildStandaloneMenu(spec.menu(), onAction));
            return new MacTray(item);
        } finally {
            ObjC.autoreleasePoolPop(pool);
        }
    }

    boolean installed() {
        return statusItem != null && !statusItem.equals(MemorySegment.NULL);
    }

    @Override
    public void setTitle(String title) {
        if (removed) {
            return;
        }
        MemorySegment button = ObjC.send(statusItem, "button");
        if (!button.equals(MemorySegment.NULL)) {
            ObjC.sendVoid(button, "setTitle:", ObjC.nsString(title));
        }
    }

    @Override
    public synchronized void remove() {
        if (removed) {
            return;
        }
        removed = true;
        MemorySegment bar = ObjC.send(ObjC.cls("NSStatusBar"), "systemStatusBar");
        ObjC.sendVoid(bar, "removeStatusItem:", statusItem);
        ObjC.release(statusItem);
    }

    private static void setTemplateImage(MemorySegment button, byte[] pngData) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(Math.max(1, pngData.length));
            if (pngData.length > 0) {
                MemorySegment.copy(pngData, 0, buffer, JAVA_BYTE, 0, pngData.length);
            }
            MemorySegment nsData = (MemorySegment) ObjC.msgSend(FunctionDescriptor.of(
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG)).invokeExact(
                    ObjC.cls("NSData"), ObjC.sel("dataWithBytes:length:"),
                    buffer, (long) pngData.length);
            MemorySegment image = ObjC.send(
                    ObjC.send(ObjC.cls("NSImage"), "alloc"), "initWithData:", nsData);
            if (!image.equals(MemorySegment.NULL)) {
                ObjC.autorelease(image);
                ObjC.sendVoidBool(image, "setTemplate:", true);
                ObjC.sendVoid(button, "setImage:", image);
            }
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        }
    }
}
