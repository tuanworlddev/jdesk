package dev.jdesk.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Thread-safe public handle for one open native window. */
public interface WindowHandle {
    WindowId id();

    EventEmitter events();

    CompletionStage<Void> show();
    CompletionStage<Void> hide();
    CompletionStage<Void> focus();
    CompletionStage<Void> setTitle(String title);
    CompletionStage<Void> setBounds(int x, int y, int width, int height);
    CompletionStage<Void> setMinimized(boolean minimized);
    CompletionStage<Void> setMaximized(boolean maximized);
    CompletionStage<Void> setFullscreen(boolean fullscreen);
    CompletionStage<Void> setAlwaysOnTop(boolean alwaysOnTop);

    /** Opens the OS print dialog for this window's current page content. */
    CompletionStage<Void> print();

    /**
     * Pops up a native context menu over this window and completes with the chosen
     * {@link MenuItem.Action} id, or empty if dismissed. Runs modally on the UI thread until
     * dismissed. Empty on platforms without native context menus.
     */
    CompletionStage<Optional<String>> showContextMenu(MenuSpec menu);

    /**
     * Registers a listener for OS file drops onto this window; it receives the absolute paths
     * of dropped files (which the HTML5 File API cannot expose). In-page HTML5 drag-and-drop
     * still works. Close the returned {@link Subscription} to stop. Empty subscription where
     * unsupported.
     */
    CompletionStage<Subscription> onFileDrop(Consumer<List<Path>> listener);

    CompletionStage<Void> close();
}
