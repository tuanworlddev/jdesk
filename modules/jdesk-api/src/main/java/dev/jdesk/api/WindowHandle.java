package dev.jdesk.api;

import java.util.concurrent.CompletionStage;

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

    CompletionStage<Void> close();
}
