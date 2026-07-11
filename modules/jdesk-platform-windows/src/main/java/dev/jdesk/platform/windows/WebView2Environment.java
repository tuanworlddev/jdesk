package dev.jdesk.platform.windows;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.ffm.NativeCallbackRegistry;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Creation and ownership of the ICoreWebView2Environment: loads WebView2Loader.dll,
 * implements the environment options COM objects in Java (including the
 * {@code jdesk} custom scheme registration, marked secure with an authority component
 * through the public ICoreWebView2CustomSchemeRegistration API), and resolves the
 * async creation by pumping the STA message loop.
 */
final class WebView2Environment implements AutoCloseable {
    private static final String SCHEME = "jdesk";
    private static final String APP_ORIGIN = "jdesk://app";

    private final NativeCallbackRegistry registry;
    private final MemorySegment environment; // owned: released in close()
    private final String browserVersion;

    private WebView2Environment(NativeCallbackRegistry registry, MemorySegment environment,
            String browserVersion) {
        this.registry = registry;
        this.environment = environment;
        this.browserVersion = browserVersion;
    }

    MemorySegment comPointer() {
        return environment;
    }

    String browserVersion() {
        return browserVersion;
    }

    static WebView2Environment create(PlatformApplicationConfig config,
            BooleanSupplier pumpOnce) {
        NativeCallbackRegistry registry =
                new NativeCallbackRegistry("webview2-environment", Arena.ofShared());
        Arena arena = registry.arena();

        SymbolLookup loader = loadLoaderLibrary(arena);
        MethodHandle createEnvironment = Linker.nativeLinker().downcallHandle(
                loader.find("CreateCoreWebView2EnvironmentWithOptions").orElseThrow(() ->
                        new JDeskException(ErrorCode.ILLEGAL_STATE,
                                "CreateCoreWebView2EnvironmentWithOptions not exported")),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

        MemorySegment schemeRegistration = buildSchemeRegistration(registry);
        MemorySegment options = buildOptions(registry, schemeRegistration);

        AtomicReference<MemorySegment> envRef = new AtomicReference<>();
        AtomicInteger completionHr = new AtomicInteger(1); // pending
        MemorySegment completedHandler = ComCallback.hrPtrHandler(registry,
                "EnvironmentCompletedHandler",
                WebView2.IID_ENVIRONMENT_COMPLETED_HANDLER,
                (self, hr, env) -> {
                    if (hr >= 0 && !env.equals(MemorySegment.NULL)) {
                        ComRuntime.addRef(env); // keep beyond the callback
                        envRef.set(env);
                    }
                    completionHr.set(hr);
                    return Hresult.S_OK;
                });

        Path userData = Path.of(System.getProperty("java.io.tmpdir"),
                "jdesk-webview2", config.applicationId());
        try {
            Files.createDirectories(userData);
        } catch (Exception e) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Cannot create WebView2 user data folder", e);
        }

        try {
            int hr = (int) createEnvironment.invokeExact(
                    MemorySegment.NULL,
                    WideStrings.alloc(arena, userData.toString()),
                    options,
                    completedHandler);
            Hresult.check(hr, "CreateCoreWebView2EnvironmentWithOptions");
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("CreateCoreWebView2EnvironmentWithOptions", t);
        }

        // Pump the STA loop until the completion handler ran.
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (completionHr.get() == 1) {
            if (System.nanoTime() > deadline || !pumpOnce.getAsBoolean()) {
                registry.close();
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "WebView2 environment creation did not complete");
            }
        }
        Hresult.check(completionHr.get(), "WebView2 environment completion");

        MemorySegment environment = envRef.get();
        String version = readBrowserVersion(environment);
        return new WebView2Environment(registry, environment, version);
    }

    private static SymbolLookup loadLoaderLibrary(Arena arena) {
        String configured = System.getProperty("jdesk.windows.webview2loader");
        String library = configured != null ? configured : "WebView2Loader.dll";
        try {
            return SymbolLookup.libraryLookup(library, arena);
        } catch (IllegalArgumentException e) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Cannot load WebView2Loader.dll. Set -Djdesk.windows.webview2loader="
                            + "<path> or put it on PATH.", e);
        }
    }

    /** ICoreWebView2CustomSchemeRegistration implemented in Java (public API only). */
    private static MemorySegment buildSchemeRegistration(NativeCallbackRegistry registry) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType outPtr = MethodType.methodType(int.class, MemorySegment.class,
                    MemorySegment.class);
            MethodType twoOut = MethodType.methodType(int.class, MemorySegment.class,
                    MemorySegment.class, MemorySegment.class);

            OutPtrFn getSchemeName = (self, out) -> writeCoTaskString(out, SCHEME);
            OutPtrFn getTreatAsSecure = (self, out) -> writeBool(out, true);
            TwoOutFn getAllowedOrigins = (self, countOut, originsOut) -> {
                // One-element LPWSTR array in CoTaskMem; caller frees per COM rules.
                MemorySegment origins = coTaskAllocPointerArray(1);
                origins.reinterpret(ADDRESS.byteSize())
                        .set(ADDRESS, 0, Win32.coTaskAllocWide(APP_ORIGIN));
                countOut.reinterpret(4).set(JAVA_INT, 0, 1);
                originsOut.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, origins);
                return Hresult.S_OK;
            };
            TwoOutFn setAllowedOrigins = (self, count, origins) -> Hresult.S_OK;
            OutPtrFn getHasAuthority = (self, out) -> writeBool(out, true);

            FunctionDescriptor outPtrDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
            FunctionDescriptor twoOutDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
            // Slots per reference doc: get_SchemeName=3, get_TreatAsSecure=4, put_TreatAsSecure=5,
            // GetAllowedOrigins=6, SetAllowedOrigins=7, get_HasAuthorityComponent=8, put=9.
            // put_TreatAsSecure/put_HasAuthorityComponent take BOOL by value.
            FunctionDescriptor putBoolDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
            BoolInFn putBool = (self, value) -> Hresult.S_OK;
            MethodType boolIn = MethodType.methodType(int.class, MemorySegment.class, int.class);

            return ComCallback.create(registry, "CustomSchemeRegistration",
                    List.of(WebView2.IID_CUSTOM_SCHEME_REGISTRATION),
                    List.of(
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(getSchemeName)),
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(getTreatAsSecure)),
                            new ComCallback.Slot(putBoolDesc, lookup.findVirtual(BoolInFn.class,
                                    "invoke", boolIn).bindTo(putBool)),
                            new ComCallback.Slot(twoOutDesc, lookup.findVirtual(TwoOutFn.class,
                                    "invoke", twoOut).bindTo(getAllowedOrigins)),
                            new ComCallback.Slot(twoOutDesc, lookup.findVirtual(TwoOutFn.class,
                                    "invoke", twoOut).bindTo(setAllowedOrigins)),
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(getHasAuthority)),
                            new ComCallback.Slot(putBoolDesc, lookup.findVirtual(BoolInFn.class,
                                    "invoke", boolIn).bindTo(putBool))));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * ICoreWebView2EnvironmentOptions + Options4 (custom schemes). Two Java COM objects:
     * QueryInterface on the options object tears off to the options4 object, whose
     * vtable layout differs.
     */
    private static MemorySegment buildOptions(NativeCallbackRegistry registry,
            MemorySegment schemeRegistration) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodType outPtr = MethodType.methodType(int.class, MemorySegment.class,
                    MemorySegment.class);
            MethodType twoOut = MethodType.methodType(int.class, MemorySegment.class,
                    MemorySegment.class, MemorySegment.class);
            FunctionDescriptor outPtrDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
            FunctionDescriptor twoOutDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

            // Options4: GetCustomSchemeRegistrations(UINT32*, ICoreWebView2CustomSchemeRegistration***)
            TwoOutFn getRegistrations = (self, countOut, arrayOut) -> {
                MemorySegment array = coTaskAllocPointerArray(1);
                ComRuntime.addRef(schemeRegistration); // caller releases
                array.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, schemeRegistration);
                countOut.reinterpret(4).set(JAVA_INT, 0, 1);
                arrayOut.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, array);
                return Hresult.S_OK;
            };
            TwoOutFn setRegistrations = (self, count, array) -> Hresult.S_OK;
            MemorySegment options4 = ComCallback.create(registry, "EnvironmentOptions4",
                    List.of(WebView2.IID_ENVIRONMENT_OPTIONS4),
                    List.of(
                            new ComCallback.Slot(twoOutDesc, lookup.findVirtual(TwoOutFn.class,
                                    "invoke", twoOut).bindTo(getRegistrations)),
                            new ComCallback.Slot(twoOutDesc, lookup.findVirtual(TwoOutFn.class,
                                    "invoke", twoOut).bindTo(setRegistrations))));

            OutPtrFn getEmptyString = (self, out) -> writeCoTaskString(out, "");
            OutPtrFn getTargetVersion = (self, out) ->
                    writeCoTaskString(out, WebView2.TARGET_COMPATIBLE_BROWSER_VERSION);
            OutPtrFn getFalse = (self, out) -> writeBool(out, false);
            OutPtrFn putIgnorePtr = (self, in) -> Hresult.S_OK;
            FunctionDescriptor putBoolDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
            BoolInFn putBool = (self, value) -> Hresult.S_OK;
            MethodType boolIn = MethodType.methodType(int.class, MemorySegment.class, int.class);

            // EnvironmentOptions slots: get/put AdditionalBrowserArguments, get/put Language,
            // get/put TargetCompatibleBrowserVersion, get/put AllowSingleSignOn (BOOL* / BOOL).
            return ComCallback.createWithTearOffs(registry, "EnvironmentOptions",
                    List.of(WebView2.IID_ENVIRONMENT_OPTIONS),
                    java.util.Map.of(WebView2.IID_ENVIRONMENT_OPTIONS4, options4),
                    List.of(
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(getEmptyString)),
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(putIgnorePtr)),
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(getEmptyString)),
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(putIgnorePtr)),
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(getTargetVersion)),
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(putIgnorePtr)),
                            new ComCallback.Slot(outPtrDesc, lookup.findVirtual(OutPtrFn.class,
                                    "invoke", outPtr).bindTo(getFalse)),
                            new ComCallback.Slot(putBoolDesc, lookup.findVirtual(BoolInFn.class,
                                    "invoke", boolIn).bindTo(putBool))));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readBrowserVersion(MemorySegment environment) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            ComRuntime.invokeChecked(environment, WebView2.ENV_GET_BROWSER_VERSION_STRING,
                    "get_BrowserVersionString",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), out);
            return WideStrings.readAndFreeCoTaskMem(out.get(ADDRESS, 0));
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static int writeCoTaskString(MemorySegment out, String value) {
        out.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, Win32.coTaskAllocWide(value));
        return Hresult.S_OK;
    }

    private static int writeBool(MemorySegment out, boolean value) {
        out.reinterpret(4).set(JAVA_INT, 0, value ? 1 : 0);
        return Hresult.S_OK;
    }

    private static MemorySegment coTaskAllocPointerArray(int count) {
        // Reuse CoTaskMemAlloc through the wide-string helper's underlying allocator.
        return Win32.coTaskAllocBytes((long) count * ADDRESS.byteSize());
    }

    interface OutPtrFn {
        int invoke(MemorySegment self, MemorySegment out);
    }

    interface TwoOutFn {
        int invoke(MemorySegment self, MemorySegment out1, MemorySegment out2);
    }

    interface BoolInFn {
        int invoke(MemorySegment self, int value);
    }

    @Override
    public void close() {
        ComRuntime.release(environment);
        registry.close();
    }
}
