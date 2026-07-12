package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Value types backing the native-integration APIs (GAP-002 asset uploads, GAP-003 PTY,
 * GAP-004 desktop integration): PTY specs, file-watch options/events, menus, tray specs,
 * system theme and asset request/response records.
 */
class NativeIntegrationTypesTest {

    // ---------------------------------------------------------------- PtySpec

    @Test
    void ptySpecOfDefaultsTo80x24InCwdWithNoEnv() {
        PtySpec spec = PtySpec.of("/bin/sh", "-c", "echo hi");
        assertThat(spec.command()).containsExactly("/bin/sh", "-c", "echo hi");
        assertThat(spec.columns()).isEqualTo(80);
        assertThat(spec.rows()).isEqualTo(24);
        assertThat(spec.workingDirectory()).isEmpty();
        assertThat(spec.environment()).isEmpty();
    }

    @Test
    void ptySpecWithersReturnUpdatedCopies() {
        PtySpec base = PtySpec.of("bash");
        assertThat(base.withSize(120, 40).columns()).isEqualTo(120);
        assertThat(base.withSize(120, 40).rows()).isEqualTo(40);
        assertThat(base.withWorkingDirectory(Path.of("/tmp")).workingDirectory())
                .contains(Path.of("/tmp"));
        assertThat(base.withEnvironment(Map.of("TERM", "xterm")).environment())
                .containsEntry("TERM", "xterm");
        // base is unchanged (record is immutable)
        assertThat(base.columns()).isEqualTo(80);
    }

    @Test
    void ptySpecCommandIsDefensivelyCopied() {
        java.util.ArrayList<String> argv = new java.util.ArrayList<>(List.of("echo"));
        PtySpec spec = new PtySpec(argv, Optional.empty(), Map.of(), 80, 24);
        argv.add("mutated");
        assertThat(spec.command()).containsExactly("echo");
        assertThatThrownBy(() -> spec.command().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ptySpecRejectsEmptyCommandAndNonPositiveSize() {
        assertThatThrownBy(() -> PtySpec.of()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PtySpec.of("sh").withSize(0, 24))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PtySpec.of("sh").withSize(80, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new PtySpec(List.of("sh"), null, Map.of(), 80, 24));
    }

    // ---------------------------------------------------------------- FileWatchOptions

    @Test
    void fileWatchOptionsConstants() {
        assertThat(FileWatchOptions.RECURSIVE.recursive()).isTrue();
        assertThat(FileWatchOptions.NON_RECURSIVE.recursive()).isFalse();
        assertThat(FileWatchOptions.RECURSIVE.coalesceWindow()).isEqualTo(Duration.ofMillis(15));
    }

    @Test
    void fileWatchOptionsWithCoalesceWindowAndValidation() {
        FileWatchOptions custom = FileWatchOptions.RECURSIVE.withCoalesceWindow(Duration.ZERO);
        assertThat(custom.coalesceWindow()).isZero();
        assertThat(custom.recursive()).isTrue();
        assertThatThrownBy(() -> FileWatchOptions.RECURSIVE.withCoalesceWindow(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new FileWatchOptions(true, null));
    }

    // ---------------------------------------------------------------- FileWatchEvent

    @Test
    void fileWatchEventAndOverflowMarker() {
        Path p = Path.of("/data/file.txt");
        FileWatchEvent created = new FileWatchEvent(p, FileWatchEvent.Kind.CREATED);
        assertThat(created.path()).isEqualTo(p);
        assertThat(created.kind()).isEqualTo(FileWatchEvent.Kind.CREATED);

        Path root = Path.of("/data");
        FileWatchEvent overflow = FileWatchEvent.overflow(root);
        assertThat(overflow.kind()).isEqualTo(FileWatchEvent.Kind.OVERFLOW);
        assertThat(overflow.path()).isEqualTo(root);

        assertThat(FileWatchEvent.Kind.values())
                .containsExactly(FileWatchEvent.Kind.CREATED, FileWatchEvent.Kind.MODIFIED,
                        FileWatchEvent.Kind.DELETED, FileWatchEvent.Kind.OVERFLOW);
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new FileWatchEvent(null, FileWatchEvent.Kind.CREATED));
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new FileWatchEvent(p, null));
    }

    // ---------------------------------------------------------------- MenuItem / MenuSpec

    @Test
    void menuItemFactories() {
        MenuItem.Action plain = MenuItem.action("save", "Save");
        assertThat(plain.id()).isEqualTo("save");
        assertThat(plain.label()).isEqualTo("Save");
        assertThat(plain.accelerator()).isEmpty();

        MenuItem.Action withAccel = MenuItem.action("save", "Save", "CmdOrCtrl+S");
        assertThat(withAccel.accelerator()).contains("CmdOrCtrl+S");

        MenuItem.Submenu submenu = MenuItem.submenu("File", plain, MenuItem.separator());
        assertThat(submenu.label()).isEqualTo("File");
        assertThat(submenu.items()).hasSize(2);
        assertThatThrownBy(() -> submenu.items().add(plain))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(MenuItem.separator()).isInstanceOf(MenuItem.Separator.class);
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new MenuItem.Action("id", "label", null));
    }

    @Test
    void menuSpecOfCopiesItems() {
        MenuItem.Action a = MenuItem.action("a", "A");
        MenuSpec spec = MenuSpec.of(a, MenuItem.separator());
        assertThat(spec.items()).hasSize(2);
        assertThatThrownBy(() -> spec.items().add(a))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------- TraySpec

    @Test
    void traySpecOfAndWithIcon() {
        MenuSpec menu = MenuSpec.of(MenuItem.action("quit", "Quit"));
        TraySpec spec = TraySpec.of("Status", menu);
        assertThat(spec.title()).isEqualTo("Status");
        assertThat(spec.iconPng()).isEmpty();
        assertThat(spec.menu()).isEqualTo(menu);

        byte[] png = {1, 2, 3};
        TraySpec withIcon = spec.withIcon(png);
        assertThat(withIcon.iconPng()).isPresent();
        assertThat(withIcon.title()).isEqualTo("Status");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new TraySpec(null, Optional.empty(), menu));
    }

    // ---------------------------------------------------------------- SystemTheme / dialog result

    @Test
    void systemThemeAndDialogResult() {
        assertThat(SystemTheme.values()).containsExactly(SystemTheme.LIGHT, SystemTheme.DARK);
        assertThat(SystemTheme.valueOf("DARK")).isEqualTo(SystemTheme.DARK);

        MessageDialogResult result = new MessageDialogResult(1, "OK");
        assertThat(result.buttonIndex()).isEqualTo(1);
        assertThat(result.buttonLabel()).isEqualTo("OK");
    }

    // ---------------------------------------------------------------- AssetRoute.Request

    @Test
    void assetRequestNormalizesHeaderKeysAndLooksThemUpCaseInsensitively() {
        AssetRoute.Request request = new AssetRoute.Request("img/1.png", "POST",
                new byte[] {9}, Map.of("Content-Type", "image/png", "RANGE", "bytes=0-"));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.body()).containsExactly(9);
        assertThat(request.header("content-type")).contains("image/png");
        assertThat(request.header("Range")).contains("bytes=0-");
        assertThat(request.header("absent")).isEmpty();
    }

    @Test
    void assetRequestGetConvenienceHasEmptyBody() {
        AssetRoute.Request request = new AssetRoute.Request("a/b", Map.of("range", "bytes=0-1"));
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.body()).isEmpty();
        assertThat(request.header("Range")).contains("bytes=0-1");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new AssetRoute.Request("p", "GET", null, Map.of()));
    }

    // ---------------------------------------------------------------- AssetRoute.Response

    @Test
    void assetResponseOfBytesClonesInputAndServesFreshStreams() throws Exception {
        byte[] source = {1, 2, 3};
        AssetRoute.Response response = AssetRoute.Response.of(source, "image/jpeg");
        source[0] = 99; // mutate after construction — response must be unaffected
        assertThat(response.contentType()).isEqualTo("image/jpeg");
        assertThat(response.contentLength()).isEqualTo(3);
        try (InputStream first = response.body().get(); InputStream second = response.body().get()) {
            assertThat(first.readAllBytes()).containsExactly(1, 2, 3);
            assertThat(second.readAllBytes()).containsExactly(1, 2, 3); // a fresh stream per call
        }
    }

    @Test
    void assetResponseOfFileReadsSizeAndContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("payload.bin");
        Files.write(file, new byte[] {7, 7, 7, 7});
        AssetRoute.Response response = AssetRoute.Response.of(file, "application/octet-stream");
        assertThat(response.contentLength()).isEqualTo(4);
        try (InputStream in = response.body().get()) {
            assertThat(in.readAllBytes()).containsExactly(7, 7, 7, 7);
        }
    }

    @Test
    void assetResponseEmptyIsAZeroLengthOctetStream() throws Exception {
        AssetRoute.Response response = AssetRoute.Response.empty();
        assertThat(response.contentType()).isEqualTo("application/octet-stream");
        assertThat(response.contentLength()).isZero();
        try (InputStream in = response.body().get()) {
            assertThat(in.readAllBytes()).isEmpty();
        }
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new AssetRoute.Response("text/plain", 0, null, Map.of()));
    }
}
