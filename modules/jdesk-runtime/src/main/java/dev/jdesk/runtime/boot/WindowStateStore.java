package dev.jdesk.runtime.boot;

import dev.jdesk.api.WindowId;
import dev.jdesk.webview.spi.WindowBounds;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Persists window bounds across runs for windows configured with
 * {@code rememberBounds(true)}. One properties file per application id under
 * {@code ~/.jdesk/window-state/} (overridable via {@code jdesk.state.dir}), keys
 * {@code <windowId>.x/.y/.width/.height}. Best-effort: I/O problems are logged and
 * never affect the application.
 */
final class WindowStateStore {
    private static final Logger LOG = System.getLogger(WindowStateStore.class.getName());

    private final Path file;

    WindowStateStore(String applicationId) {
        String configured = System.getProperty("jdesk.state.dir");
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".jdesk", "window-state")
                : Path.of(configured);
        this.file = directory.resolve(applicationId + ".properties");
    }

    /** Positions beyond this are junk (e.g. Windows' -32000 minimized placeholder). */
    private static final int MAX_POSITION = 30_000;

    synchronized Optional<WindowBounds> load(WindowId windowId) {
        Properties properties = read();
        String prefix = windowId.value() + ".";
        try {
            int x = Integer.parseInt(properties.getProperty(prefix + "x"));
            int y = Integer.parseInt(properties.getProperty(prefix + "y"));
            int width = Integer.parseInt(properties.getProperty(prefix + "width"));
            int height = Integer.parseInt(properties.getProperty(prefix + "height"));
            if (width < 1 || height < 1 || width > 32767 || height > 32767
                    || Math.abs(x) > MAX_POSITION || Math.abs(y) > MAX_POSITION) {
                return Optional.empty();
            }
            return Optional.of(new WindowBounds(x, y, width, height));
        } catch (NumberFormatException | NullPointerException e) {
            return Optional.empty(); // absent or corrupt entry: use the configured size
        }
    }

    synchronized void save(WindowId windowId, WindowBounds bounds) {
        if (bounds.width() < 1 || bounds.height() < 1
                || Math.abs(bounds.x()) > MAX_POSITION || Math.abs(bounds.y()) > MAX_POSITION) {
            return; // minimized/off-screen placeholder readings are not worth restoring
        }
        Properties properties = read();
        String prefix = windowId.value() + ".";
        properties.setProperty(prefix + "x", Integer.toString(bounds.x()));
        properties.setProperty(prefix + "y", Integer.toString(bounds.y()));
        properties.setProperty(prefix + "width", Integer.toString(bounds.width()));
        properties.setProperty(prefix + "height", Integer.toString(bounds.height()));
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "JDesk window state");
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Window state not persisted", e);
        }
    }

    private Properties read() {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "Window state not readable", e);
            }
        }
        return properties;
    }
}
