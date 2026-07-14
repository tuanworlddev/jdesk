package dev.jdesk.platform.macos;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import dev.jdesk.api.ShareContent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;

/**
 * macOS share sheet ({@code NSSharingServicePicker}) and biometric availability
 * ({@code LAContext canEvaluatePolicy:}). No cross-platform framework offers a first-class share,
 * so it is a differentiator; biometrics gate sensitive actions on Touch ID.
 *
 * <p>Honest status: both are structurally implemented and compile-verified. The share popover and a
 * Touch-ID prompt require a real GUI + enrolled hardware, so — like the other GUI/hardware gestures
 * here — they are exercised interactively/in CI, not by a unit test. The actual biometric
 * <em>prompt</em> ({@code evaluatePolicy:reply:}) needs a {@code (BOOL, NSError*)} reply-block ABI
 * the current {@link ObjCBlock} helper does not yet build; {@link #biometricsAvailable()} reports
 * usability, which is what gates a prompt.
 */
final class MacDesktopServices {
    private static final Logger LOG = System.getLogger(MacDesktopServices.class.getName());
    // LAPolicyDeviceOwnerAuthenticationWithBiometrics
    private static final long LA_POLICY_BIOMETRICS = 1;
    // NSMinYEdge for the picker popover
    private static final long NS_MIN_Y_EDGE = 1;

    static {
        // Best-effort: LocalAuthentication is not auto-loaded into a raw FFM process.
        try {
            SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/LocalAuthentication.framework/LocalAuthentication",
                    java.lang.foreign.Arena.global());
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "LocalAuthentication framework not loadable", e);
        }
    }

    private MacDesktopServices() {
    }

    static boolean biometricsAvailable() {
        MemorySegment pool = ObjC.autoreleasePoolPush();
        try {
            MemorySegment context = ObjC.send(
                    ObjC.send(ObjC.cls("LAContext"), "alloc"), "init");
            ObjC.autorelease(context);
            // - (BOOL)canEvaluatePolicy:(LAPolicy)policy error:(NSError**)error
            byte ok = (byte) ObjC.msgSend(FunctionDescriptor.of(JAVA_BYTE,
                    ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
                    .invokeExact(context, ObjC.sel("canEvaluatePolicy:error:"),
                            LA_POLICY_BIOMETRICS, MemorySegment.NULL);
            return ok != 0;
        } catch (Throwable t) {
            LOG.log(Level.DEBUG, "Biometrics unavailable", t);
            return false;
        } finally {
            ObjC.autoreleasePoolPop(pool);
        }
    }

    static boolean share(ShareContent content) {
        MemorySegment nsApp = ObjC.send(ObjC.cls("NSApplication"), "sharedApplication");
        MemorySegment keyWindow = ObjC.send(nsApp, "keyWindow");
        if (keyWindow.equals(MemorySegment.NULL)) {
            return false;
        }
        MemorySegment view = ObjC.send(keyWindow, "contentView");
        if (view.equals(MemorySegment.NULL)) {
            return false;
        }
        MemorySegment pool = ObjC.autoreleasePoolPush();
        try {
            MemorySegment items = ObjC.send(
                    ObjC.send(ObjC.cls("NSMutableArray"), "alloc"), "init");
            ObjC.autorelease(items);
            if (!content.text().isBlank()) {
                ObjC.sendVoid(items, "addObject:", ObjC.nsString(content.text()));
            }
            for (String url : content.urls()) {
                MemorySegment nsUrl = url.contains("://")
                        ? ObjC.send(ObjC.cls("NSURL"), "URLWithString:", ObjC.nsString(url))
                        : ObjC.send(ObjC.cls("NSURL"), "fileURLWithPath:", ObjC.nsString(url));
                if (!nsUrl.equals(MemorySegment.NULL)) {
                    ObjC.sendVoid(items, "addObject:", nsUrl);
                }
            }
            MemorySegment picker = ObjC.send(
                    ObjC.send(ObjC.cls("NSSharingServicePicker"), "alloc"),
                    "initWithItems:", items);
            ObjC.autorelease(picker);

            // Anchor the popover to a small rect in the content view's top-left corner.
            java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined();
            try {
                MemorySegment rect = arena.allocate(ObjC.NSRECT);
                rect.set(JAVA_DOUBLE, 0, 0.0);   // x
                rect.set(JAVA_DOUBLE, 8, 0.0);   // y
                rect.set(JAVA_DOUBLE, 16, 1.0);  // width
                rect.set(JAVA_DOUBLE, 24, 1.0);  // height
                // - (void)showRelativeToRect:(NSRect)rect ofView:(NSView*)view
                //         preferredEdge:(NSRectEdge)edge
                ObjC.msgSend(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ObjC.NSRECT,
                        ADDRESS, JAVA_LONG))
                        .invokeExact(picker,
                                ObjC.sel("showRelativeToRect:ofView:preferredEdge:"),
                                rect, view, NS_MIN_Y_EDGE);
            } finally {
                arena.close();
            }
            return true;
        } catch (Throwable t) {
            throw ObjC.rethrow(t);
        } finally {
            ObjC.autoreleasePoolPop(pool);
        }
    }
}
