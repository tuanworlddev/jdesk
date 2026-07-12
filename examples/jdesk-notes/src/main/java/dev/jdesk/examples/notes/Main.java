package dev.jdesk.examples.notes;

import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.JDeskApplication;
import dev.jdesk.api.LifecycleListener;
import dev.jdesk.api.MenuItem;
import dev.jdesk.api.MenuSpec;
import dev.jdesk.api.TrayHandle;
import dev.jdesk.api.TraySpec;
import dev.jdesk.api.WindowConfig;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.config.Capabilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDesk Notes: a real tabbed note editor built only on public JDesk APIs.
 *
 * <ul>
 *   <li>Browser-style tabs (with a {@code +}), a Files sidebar, native New/Open/Save/Save&nbsp;As
 *       dialogs, and open tabs that survive a restart (session persistence).</li>
 *   <li>A system tray item (Show / Start with Windows / Quit). Closing the window hides it to
 *       the tray instead of quitting, so the app keeps running and reopens on tray click.</li>
 * </ul>
 */
public final class Main {
    private static final WindowId MAIN = new WindowId("main");

    private Main() {
    }

    public static void main(String[] args) {
        NotesService notes = new NotesService();
        AtomicReference<ApplicationHandle> appRef = new AtomicReference<>();
        AtomicReference<TrayHandle> trayRef = new AtomicReference<>();
        AtomicBoolean trayActive = new AtomicBoolean(false);

        JDeskApplication.Builder builder = JDeskApplication.builder()
                .id("dev.jdesk.examples.notes")
                .commands(NotesServiceCommands.create(notes))
                .capabilities(Capabilities.fromResource(
                        Main.class.getModule(), "jdesk-capabilities.json"))
                .singleInstance(argv -> onActivate(appRef, argv))
                .lifecycle(new LifecycleListener() {
                    @Override
                    public void onReady(ApplicationHandle application) {
                        appRef.set(application);
                        notes.bind(application);
                        installTray(application, trayRef, trayActive);
                        // Drag-and-drop: dropping files onto the window opens each in a tab.
                        application.window(MAIN).ifPresent(w -> w.onFileDrop(paths ->
                                paths.forEach(p -> w.events()
                                        .emit("notes.openPath", Map.of("path", p.toString())))));
                    }

                    @Override
                    public boolean onCloseRequested(WindowId windowId) {
                        // Close-to-tray: keep running in the tray instead of quitting, but only
                        // when the tray actually installed (else the window would be untappable).
                        if (trayActive.get() && MAIN.equals(windowId)) {
                            ApplicationHandle app = appRef.get();
                            if (app != null) {
                                app.window(windowId).ifPresent(w -> w.hide());
                            }
                            return false;
                        }
                        return true;
                    }
                })
                .window(WindowConfig.builder()
                        .id(MAIN.value())
                        .title("JDesk Notes")
                        .size(1040, 700)
                        .minSize(640, 400)
                        .entry("jdesk://app/index.html")
                        .build());
        String devUrl = System.getProperty("jdesk.devUrl");
        if (Boolean.getBoolean("jdesk.dev") && devUrl != null) {
            builder.devServerUrl(devUrl);
        }
        System.exit(builder.run(args));
    }

    /** Tray menu with the autostart item reflecting the current registry state as a checkmark. */
    private static MenuSpec trayMenu() {
        return MenuSpec.of(
                MenuItem.action("show", "Show Notes"),
                MenuItem.check("autostart", "Start with Windows", WindowsAutostart.isEnabled()),
                MenuItem.separator(),
                MenuItem.action("quit", "Quit JDesk Notes"));
    }

    private static void installTray(ApplicationHandle app, AtomicReference<TrayHandle> trayRef,
            AtomicBoolean trayActive) {
        app.createTrayItem(TraySpec.of("JDesk Notes", trayMenu()), id -> {
            switch (id) {
                case "show" -> app.window(MAIN).ifPresent(w -> {
                    w.show();
                    w.focus();
                });
                case "autostart" -> {
                    WindowsAutostart.toggle();
                    // Rebuild the menu so the checkmark reflects the new state next time.
                    TrayHandle handle = trayRef.get();
                    if (handle != null) {
                        handle.setMenu(trayMenu());
                    }
                }
                case "quit" -> app.requestStop();
                default -> {
                }
            }
        }).whenComplete((handle, error) -> {
            if (handle != null) {
                trayRef.set(handle);
                trayActive.set(true);
            } else {
                System.getLogger(Main.class.getName()).log(System.Logger.Level.WARNING,
                        "System tray unavailable; the window will close normally", error);
            }
        });
    }

    /**
     * A second launch of the single-instance app: focus the running window and open any file
     * path passed on the new command line as a tab (via a page event the frontend listens for).
     */
    private static void onActivate(AtomicReference<ApplicationHandle> appRef, List<String> argv) {
        ApplicationHandle app = appRef.get();
        if (app == null) {
            return;
        }
        app.window(MAIN).ifPresent(w -> {
            w.show();
            w.focus();
            for (String arg : argv) {
                if (!arg.startsWith("-") && java.nio.file.Files.isRegularFile(Path.of(arg))) {
                    w.events().emit("notes.openPath",
                            Map.of("path", Path.of(arg).toAbsolutePath().toString()));
                }
            }
        });
    }

    /**
     * Registers/unregisters the app under the current-user Windows startup key
     * (HKCU\...\Run) so it auto-runs at login. Best-effort and Windows-only; the target is the
     * process's own launcher, so it is meaningful for the packaged app (a real {@code .exe}),
     * not a bare {@code java} dev launch.
     */
    static final class WindowsAutostart {
        private static final System.Logger LOG = System.getLogger(WindowsAutostart.class.getName());
        private static final String RUN_KEY =
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        private static final String VALUE_NAME = "JDeskNotes";

        private WindowsAutostart() {
        }

        static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                    .contains("win");
        }

        static boolean isEnabled() {
            if (!isWindows()) {
                return false;
            }
            try {
                Process p = new ProcessBuilder("reg", "query", RUN_KEY, "/v", VALUE_NAME)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                return p.waitFor() == 0;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }

        static void toggle() {
            if (!isWindows()) {
                LOG.log(System.Logger.Level.INFO, "Autostart is Windows-only; ignored");
                return;
            }
            if (isEnabled()) {
                disable();
            } else {
                enable();
            }
        }

        static void enable() {
            String command = ProcessHandle.current().info().command().orElse("");
            if (command.isBlank()) {
                LOG.log(System.Logger.Level.WARNING, "Cannot resolve launcher path for autostart");
                return;
            }
            run("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ",
                    "/d", '"' + command + '"', "/f");
            LOG.log(System.Logger.Level.INFO, "Autostart enabled -> {0}", command);
        }

        static void disable() {
            run("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
            LOG.log(System.Logger.Level.INFO, "Autostart disabled");
        }

        private static void run(String... command) {
            try {
                new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.log(System.Logger.Level.WARNING, "Autostart registry update failed", e);
            }
        }
    }
}
