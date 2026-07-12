package dev.jdesk.platform.macos;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.util.function.Consumer;

/**
 * Installs a minimal {@code NSApplicationDelegate} ({@code JDeskAppDelegate}) implementing
 * {@code application:openURLs:} so the app receives {@code scheme://} deep links while
 * running. Only that one delegate method is provided, so default AppKit behaviour is
 * unaffected.
 *
 * <p>Honest status: the delegate is installed and the app keeps running (verified). Actually
 * <em>routing</em> a {@code scheme://} link to the app is a Launch Services / bundle concern
 * (needs a signed, installed {@code .app} whose Info.plist declares the scheme — see
 * {@code InfoPlistCustomizer}); that cannot be exercised from an unbundled dev run.
 */
final class MacOpenUrl {
    private static final Logger LOG = System.getLogger(MacOpenUrl.class.getName());

    private static volatile MemorySegment delegateInstance;
    private static volatile Consumer<URI> handler;

    private MacOpenUrl() {
    }

    static synchronized void install(MemorySegment nsApp, Consumer<URI> openUrlHandler) {
        handler = openUrlHandler;
        if (delegateInstance == null) {
            MemorySegment cls;
            try {
                cls = new ObjCClassBuilder("JDeskAppDelegate")
                        .protocol("NSApplicationDelegate")
                        .method("application:openURLs:", "v@:@@",
                                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                                MethodHandles.lookup().findStatic(MacOpenUrl.class, "impOpenUrls",
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
        }
        ObjC.sendVoid(nsApp, "setDelegate:", delegateInstance);
    }

    @SuppressWarnings("unused") // invoked from AppKit via the JDeskAppDelegate IMP
    static void impOpenUrls(MemorySegment self, MemorySegment cmd, MemorySegment app,
            MemorySegment urls) {
        try {
            Consumer<URI> current = handler;
            if (current == null) {
                return;
            }
            long count = ObjC.sendLong(urls, "count");
            for (long i = 0; i < count; i++) {
                String absolute = ObjC.javaString(
                        ObjC.send(ObjC.sendIndexed(urls, "objectAtIndex:", i), "absoluteString"));
                if (absolute != null && !absolute.isEmpty()) {
                    try {
                        current.accept(URI.create(absolute));
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "openURL handler threw for " + absolute, e);
                    }
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.ERROR, "application:openURLs: dispatch failed", t);
        }
    }
}
