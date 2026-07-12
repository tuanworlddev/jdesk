package dev.jdesk.examples.notes;

import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.runtime.config.Capabilities;

/**
 * JDesk Notes: a real note editor built only on public JDesk APIs.
 *
 * <ul>
 *   <li>New / Open / Save / Save&nbsp;As, where Open and Save&nbsp;As raise the OS-native file
 *       dialogs through {@link ApplicationHandle#showOpenDialog}/{@code showSaveDialog}.</li>
 *   <li>Commands come from {@link NotesService} via compile-time {@code NotesServiceCommands}
 *       (no reflection scanning).</li>
 *   <li>Deny-by-default capabilities: {@code jdesk-capabilities.json} grants {@code notes:use}
 *       to the main window only.</li>
 * </ul>
 *
 * <p>Run on Windows with the real WebView2 engine:</p>
 * <pre>{@code
 * ./gradlew :examples:jdesk-notes:run -PjdeskPlatform=windows \
 *     -PjdeskWebView2Loader=<abs path to WebView2Loader.dll>
 * }</pre>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        NotesService notes = new NotesService();
        JDeskApplication.Builder builder = JDeskApplication.builder()
                .id("dev.jdesk.examples.notes")
                .commands(NotesServiceCommands.create(notes))
                .capabilities(Capabilities.fromResource(
                        Main.class.getModule(), "jdesk-capabilities.json"))
                .lifecycle(new LifecycleListener() {
                    @Override
                    public void onReady(ApplicationHandle application) {
                        notes.bind(application);
                    }
                })
                .window(WindowConfig.builder()
                        .id("main")
                        .title("JDesk Notes")
                        .size(940, 680)
                        .minSize(560, 360)
                        .entry("jdesk://app/index.html")
                        .build());
        String devUrl = System.getProperty("jdesk.devUrl");
        if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
            builder.devServerUrl(devUrl);
        }
        System.exit(builder.run(args));
    }
}
