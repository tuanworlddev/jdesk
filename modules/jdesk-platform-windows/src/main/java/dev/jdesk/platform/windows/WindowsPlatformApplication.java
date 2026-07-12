package dev.jdesk.platform.windows;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.UiDispatcher;
import dev.jdesk.ffm.NativeHandle;
import dev.jdesk.webview.spi.NativeWindowConfig;
import dev.jdesk.webview.spi.PlatformApplication;
import dev.jdesk.webview.spi.PlatformApplicationConfig;
import dev.jdesk.webview.spi.PlatformWindow;
import dev.jdesk.ffm.NativeCallbackRegistry;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.net.URI;
import dev.jdesk.api.MessageDialog;
import dev.jdesk.api.MessageDialogResult;

/**
 * One Win32 application: the constructing thread becomes the STA/UI thread
 * (spec section 7: WebView2 runs on an STA thread with a Win32 message pump; COM is
 * initialized and uninitialized on the same thread).
 */
final class WindowsPlatformApplication extends NativeHandle implements PlatformApplication {
    private final PlatformApplicationConfig config;
    private final WindowsUiDispatcher dispatcher;
    private final WebView2Environment environment;
    private final Arena msgArena = Arena.ofShared();
    private final List<NativeCallbackRegistry> streamRegistries = new CopyOnWriteArrayList<>();
    private volatile boolean stopRequested;
    private volatile WindowsPtyBackend ptyBackend;

    WindowsPlatformApplication(PlatformApplicationConfig config) {
        super("WindowsPlatformApplication");
        this.config = config;
        int hr = Win32.coInitializeEx(Win32.COINIT_APARTMENTTHREADED);
        // S_OK or S_FALSE (already initialized) are both acceptable.
        Hresult.check(hr, "CoInitializeEx");
        this.dispatcher = new WindowsUiDispatcher(config.devMode());
        this.environment = WebView2Environment.create(config, this::pumpOnce);
        markOpen();
    }

    @Override
    public UiDispatcher ui() {
        return dispatcher;
    }

    WebView2Environment environment() {
        return environment;
    }

    PlatformApplicationConfig config() {
        return config;
    }

    @Override
    public PlatformWindow createWindow(NativeWindowConfig windowConfig) {
        requireOpen();
        dispatcher.assertUiThread();
        return new WindowsWindow(this, windowConfig);
    }

    @Override
    public java.util.Optional<dev.jdesk.webview.spi.PtyBackend> ptyBackend() {
        WindowsPtyBackend backend = ptyBackend;
        if (backend == null) {
            synchronized (this) {
                backend = ptyBackend;
                if (backend == null) {
                    backend = new WindowsPtyBackend();
                    ptyBackend = backend;
                }
            }
        }
        return java.util.Optional.of(backend);
    }

    @Override public dev.jdesk.api.SystemTheme systemTheme() {
        requireOpen(); dispatcher.assertUiThread();
        return WindowsDesktop.systemTheme();
    }
    @Override public byte[] readClipboard(String type) {
        requireOpen(); dispatcher.assertUiThread();
        return WindowsDesktop.readClipboard(type);
    }
    @Override public void writeClipboard(String type, byte[] data) {
        requireOpen(); dispatcher.assertUiThread();
        WindowsDesktop.writeClipboard(type, data);
    }
    @Override public void setDockBadge(String label) {
        requireOpen(); dispatcher.assertUiThread();
        WindowsDesktop.setDockBadge(label);
    }
    @Override public void setApplicationIcon(byte[] pngData) {
        requireOpen(); dispatcher.assertUiThread();
        WindowsDesktop.setApplicationIcon(pngData);
    }
    @Override public Runnable registerGlobalShortcut(String accelerator, Runnable callback) {
        requireOpen(); dispatcher.assertUiThread();
        return WindowsShellIntegration.registerHotkey(accelerator, callback);
    }
    @Override public dev.jdesk.webview.spi.TrayControl createTrayItem(dev.jdesk.api.TraySpec spec,
            java.util.function.Consumer<String> onAction) {
        requireOpen(); dispatcher.assertUiThread();
        return WindowsShellIntegration.createTray(spec, onAction);
    }
    @Override public void showNotification(String title, String body) {
        requireOpen(); dispatcher.assertUiThread();
        WindowsShellIntegration.notify(title, body);
    }

    @Override public void openExternal(URI uri) {
        requireOpen(); dispatcher.assertUiThread(); Win32.openExternal(uri.toString());
    }
    @Override public dev.jdesk.api.SecretStore secrets(String applicationId) {
        // DPAPI calls are thread-safe; no UI-thread assertion by design.
        return new WindowsSecretStore(applicationId);
    }
    @Override public String readClipboardText(){requireOpen();dispatcher.assertUiThread();return Win32.readClipboardText();}
    @Override public void writeClipboardText(String text){requireOpen();dispatcher.assertUiThread();Win32.writeClipboardText(text);}
    @Override public MessageDialogResult showMessageDialog(MessageDialog dialog){
        requireOpen();dispatcher.assertUiThread();return Win32.showMessageDialog(dialog);
    }
    @Override public dev.jdesk.api.FileDialogResult showOpenDialog(
            dev.jdesk.api.FileDialog.OpenDialog dialog) {
        requireOpen(); dispatcher.assertUiThread();
        return WindowsFileDialog.open(dialog);
    }
    @Override public dev.jdesk.api.FileDialogResult showSaveDialog(
            dev.jdesk.api.FileDialog.SaveDialog dialog) {
        requireOpen(); dispatcher.assertUiThread();
        return WindowsFileDialog.save(dialog);
    }
    @Override public void printFile(dev.jdesk.api.PrintJob job) {
        // ShellExecute "print"/"printto" uses the file's registered handler; it does not
        // honor copies/paperSize (that needs a full print API — a documented gap).
        Win32.shellPrint(job.filePath(), job.printerName().orElse(null));
    }

    @Override
    public void runEventLoop() {
        requireOpen();
        dispatcher.assertUiThread();
        MemorySegment msg = msgArena.allocate(Win32.MSG);
        while (!stopRequested) {
            int result = Win32.getMessage(msg, MemorySegment.NULL, 0, 0);
            if (result == 0 || result == -1) {
                break; // WM_QUIT or failure
            }
            Win32.translateMessage(msg);
            Win32.dispatchMessage(msg);
        }
    }

    /**
     * Pumps one message iteration; used to wait for async COM completions during
     * initialization. Returns false when WM_QUIT was consumed.
     */
    boolean pumpOnce() {
        MemorySegment msg = msgArena.allocate(Win32.MSG);
        int result = Win32.getMessage(msg, MemorySegment.NULL, 0, 0);
        if (result == 0 || result == -1) {
            stopRequested = true;
            return false;
        }
        Win32.translateMessage(msg);
        Win32.dispatchMessage(msg);
        return true;
    }

    /** Pumps until {@code condition} holds or the deadline passes. UI thread only. */
    void pumpUntil(BooleanSupplier condition, long timeoutMillis, String what) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Timed out waiting for " + what);
            }
            if (!pumpOnce()) {
                throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                        "Event loop ended while waiting for " + what);
            }
        }
    }

    @Override
    public void requestStop() {
        stopRequested = true;
        // Post through the dispatcher so PostQuitMessage runs on the UI thread.
        dispatcher.execute(() -> Win32.postQuitMessage(0));
    }

    /** Tracks a per-response IStream registry; released at application teardown. */
    void adoptStreamRegistry(NativeCallbackRegistry registry) {
        streamRegistries.add(registry);
    }

    @Override
    protected void releaseNative() {
        dispatcher.assertUiThread();
        for (NativeCallbackRegistry streamRegistry : streamRegistries) {
            streamRegistry.close();
        }
        streamRegistries.clear();
        environment.close();
        dispatcher.close();
        msgArena.close();
        Win32.coUninitialize();
    }
}
