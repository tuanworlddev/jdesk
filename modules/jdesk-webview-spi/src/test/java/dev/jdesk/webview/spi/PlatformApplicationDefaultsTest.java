package dev.jdesk.webview.spi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.FileDialog;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.MessageDialog;
import dev.jdesk.api.MessageDialogResult;
import dev.jdesk.api.PrintJob;
import dev.jdesk.api.UiDispatcher;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An adapter with no dialog/print/secret support fails loudly through the SPI defaults. */
class PlatformApplicationDefaultsTest {

    private static final PlatformApplication BARE = new PlatformApplication() {
        @Override public UiDispatcher ui() {
            return null;
        }
        @Override public PlatformWindow createWindow(NativeWindowConfig config) {
            return null;
        }
        @Override public void openExternal(URI uri) {
        }
        @Override public String readClipboardText() {
            return "";
        }
        @Override public void writeClipboardText(String text) {
        }
        @Override public MessageDialogResult showMessageDialog(MessageDialog dialog) {
            return new MessageDialogResult(0, "OK");
        }
        @Override public void runEventLoop() {
        }
        @Override public void requestStop() {
        }
        @Override public void close() {
        }
    };

    @Test
    void dialogAndPrintAndSecretDefaultsThrowIllegalState() {
        assertThatThrownBy(() -> BARE.showOpenDialog(
                FileDialog.OpenDialog.ofType("t")))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.code())
                                .isEqualTo(ErrorCode.ILLEGAL_STATE));
        assertThatThrownBy(() -> BARE.showSaveDialog(
                FileDialog.SaveDialog.withName("t", "f.txt")))
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.printFile(PrintJob.of("/tmp/x.pdf")))
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.secrets("dev.example.app"))
                .isInstanceOf(JDeskException.class);
    }

    @Test
    void nativeIntegrationDefaultsThrowOrNoOpAsDocumented() {
        // Unsupported desktop-integration calls fail loudly (GAP-004).
        assertThatThrownBy(BARE::systemTheme).isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.readClipboard("app/x")).isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.writeClipboard("app/x", new byte[] {1}))
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.setDockBadge("3")).isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.setApplicationIcon(new byte[] {1}))
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.createTrayItem(
                dev.jdesk.api.TraySpec.of("t", dev.jdesk.api.MenuSpec.of()), id -> { }))
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.registerGlobalShortcut("CmdOrCtrl+K", () -> { }))
                .isInstanceOf(JDeskException.class);
        assertThatThrownBy(() -> BARE.showNotification("t", "b")).isInstanceOf(JDeskException.class);

        // Menu bar / open-url handler are no-ops on adapters without those concepts.
        BARE.setApplicationMenu(dev.jdesk.api.MenuSpec.of(), id -> { });
        BARE.setOpenUrlHandler(uri -> { });

        // File-watch / PTY backends are optional capabilities; absent by default.
        org.assertj.core.api.Assertions.assertThat(BARE.fileWatchBackend()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(BARE.ptyBackend()).isEmpty();
    }

    @Test
    void openDialogFilterListIsAcceptedEmpty() {
        // Sanity on the record path used above.
        assertThatThrownBy(() -> BARE.showOpenDialog(
                new FileDialog.OpenDialog("t", java.util.Optional.empty(), List.of(), true, false)))
                .isInstanceOf(JDeskException.class);
    }
}
