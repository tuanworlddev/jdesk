package dev.jdesk.platform.macos;

import static java.lang.foreign.ValueLayout.ADDRESS;

import dev.jdesk.api.InteractiveNotification;
import dev.jdesk.api.NotificationResponse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delivers an {@link InteractiveNotification} through the legacy, bundle-free
 * {@code NSUserNotification} API (which — unlike {@code UNUserNotificationCenter} — needs no signed
 * bundle, matching {@link MacPlatformApplication#showNotification(String, String)}). Action buttons,
 * an additional-actions menu, and an inline reply field are all supported; the user's choice is
 * routed back through an {@code NSUserNotificationCenterDelegate} to complete the returned future.
 *
 * <p>Honest status: delivery + delegate installation are structurally implemented and
 * compile-verified. The activation callback itself only fires on a real user click/reply, so — like
 * the other GUI gestures in this adapter — it is verified interactively/in CI, not by a unit test.
 */
final class MacInteractiveNotification {
    private static final Logger LOG = System.getLogger(MacInteractiveNotification.class.getName());

    // NSUserNotificationActivationType
    private static final long ACTION_BUTTON = 2;
    private static final long REPLIED = 3;
    private static final long ADDITIONAL_ACTION = 4;

    private static final ConcurrentHashMap<String, PendingNotification> PENDING =
            new ConcurrentHashMap<>();
    private static volatile MemorySegment delegateInstance;

    private record PendingNotification(CompletableFuture<NotificationResponse> future,
            List<InteractiveNotification.Action> actions) {
    }

    private MacInteractiveNotification() {
    }

    static CompletableFuture<NotificationResponse> show(InteractiveNotification request) {
        MemorySegment center = ObjC.send(
                ObjC.cls("NSUserNotificationCenter"), "defaultUserNotificationCenter");
        if (center.equals(MemorySegment.NULL)) {
            return CompletableFuture.failedFuture(new dev.jdesk.api.JDeskException(
                    dev.jdesk.api.ErrorCode.ILLEGAL_STATE,
                    "User notifications unavailable (needs a signed app bundle)"));
        }
        installDelegate(center);

        CompletableFuture<NotificationResponse> future = new CompletableFuture<>();
        String identifier = UUID.randomUUID().toString();
        PENDING.put(identifier, new PendingNotification(future, request.actions()));

        MemorySegment pool = ObjC.autoreleasePoolPush();
        try {
            MemorySegment note = ObjC.send(
                    ObjC.send(ObjC.cls("NSUserNotification"), "alloc"), "init");
            ObjC.autorelease(note);
            ObjC.sendVoid(note, "setIdentifier:", ObjC.nsString(identifier));
            ObjC.sendVoid(note, "setTitle:", ObjC.nsString(request.title()));
            ObjC.sendVoid(note, "setInformativeText:", ObjC.nsString(request.body()));

            List<InteractiveNotification.Action> actions = request.actions();
            if (!actions.isEmpty()) {
                ObjC.sendVoidBool(note, "setHasActionButton:", true);
                ObjC.sendVoid(note, "setActionButtonTitle:",
                        ObjC.nsString(actions.getFirst().title()));
                if (actions.size() > 1) {
                    MemorySegment array = ObjC.send(
                            ObjC.send(ObjC.cls("NSMutableArray"), "alloc"), "init");
                    ObjC.autorelease(array);
                    for (int i = 1; i < actions.size(); i++) {
                        InteractiveNotification.Action action = actions.get(i);
                        MemorySegment nsAction = ObjC.send(ObjC.cls("NSUserNotificationAction"),
                                "actionWithIdentifier:title:",
                                ObjC.nsString(action.id()), ObjC.nsString(action.title()));
                        ObjC.sendVoid(array, "addObject:", nsAction);
                    }
                    ObjC.sendVoid(note, "setAdditionalActions:", array);
                }
            }
            if (request.allowReply()) {
                ObjC.sendVoidBool(note, "setHasReplyButton:", true);
                if (!request.replyPlaceholder().isEmpty()) {
                    ObjC.sendVoid(note, "setResponsePlaceholder:",
                            ObjC.nsString(request.replyPlaceholder()));
                }
            }
            ObjC.sendVoid(center, "deliverNotification:", note);
        } catch (RuntimeException e) {
            PENDING.remove(identifier);
            future.completeExceptionally(e);
        } finally {
            ObjC.autoreleasePoolPop(pool);
        }
        return future;
    }

    private static synchronized void installDelegate(MemorySegment center) {
        if (delegateInstance != null) {
            return;
        }
        MemorySegment cls;
        try {
            cls = new ObjCClassBuilder("JDeskNotificationDelegate")
                    .protocol("NSUserNotificationCenterDelegate")
                    .method("userNotificationCenter:didActivateNotification:", "v@:@@",
                            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                            MethodHandles.lookup().findStatic(MacInteractiveNotification.class,
                                    "impDidActivate",
                                    MethodType.methodType(void.class, MemorySegment.class,
                                            MemorySegment.class, MemorySegment.class,
                                            MemorySegment.class)))
                    .register();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        MemorySegment instance = ObjC.send(ObjC.send(cls, "alloc"), "init");
        ObjC.retain(instance); // process-lifetime delegate
        delegateInstance = instance;
        ObjC.sendVoid(center, "setDelegate:", delegateInstance);
    }

    @SuppressWarnings("unused") // invoked from AppKit via the JDeskNotificationDelegate IMP
    static void impDidActivate(MemorySegment self, MemorySegment cmd, MemorySegment center,
            MemorySegment notification) {
        try {
            String identifier = ObjC.javaString(ObjC.send(notification, "identifier"));
            PendingNotification pending = identifier == null ? null : PENDING.remove(identifier);
            if (pending == null) {
                return;
            }
            long type = ObjC.sendLong(notification, "activationType");
            NotificationResponse response;
            if (type == REPLIED) {
                MemorySegment attributed = ObjC.send(notification, "response");
                String text = attributed.equals(MemorySegment.NULL) ? ""
                        : ObjC.javaString(ObjC.send(attributed, "string"));
                String actionId = pending.actions().isEmpty() ? null
                        : pending.actions().getFirst().id();
                response = NotificationResponse.reply(actionId, text == null ? "" : text);
            } else if (type == ACTION_BUTTON && !pending.actions().isEmpty()) {
                response = NotificationResponse.action(pending.actions().getFirst().id());
            } else if (type == ADDITIONAL_ACTION) {
                MemorySegment action = ObjC.send(notification, "additionalActivationAction");
                String actionId = action.equals(MemorySegment.NULL) ? null
                        : ObjC.javaString(ObjC.send(action, "identifier"));
                response = actionId == null ? NotificationResponse.dismissed()
                        : NotificationResponse.action(actionId);
            } else {
                response = NotificationResponse.dismissed();
            }
            pending.future().complete(response);
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "notification activation dispatch failed", t);
        }
    }
}
