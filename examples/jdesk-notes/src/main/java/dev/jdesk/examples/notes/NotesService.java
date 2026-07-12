package dev.jdesk.examples.notes;

import dev.jdesk.api.ApplicationHandle;
import dev.jdesk.api.DesktopCommand;
import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.FileDialog;
import dev.jdesk.api.InvocationContext;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.RequiresCapability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The Notes example's feature service: tabbed file open/save backed by real OS-native
 * dialogs, a folder listing for the Files sidebar, and opaque session persistence so open
 * tabs survive a restart.
 *
 * <p>Open and Save&nbsp;As invoke {@link ApplicationHandle#showOpenDialog}/{@code showSaveDialog}
 * (comdlg32 {@code IFileDialog} on Windows, NSOpen/SavePanel on macOS, GtkFileChooser on
 * Linux). All file I/O runs off the UI thread on the command's virtual thread. Session state
 * is stored verbatim as the JSON string the page produces — the page owns the schema, Java is
 * just a store.</p>
 */
public class NotesService {

    /** Editor safety cap: refuse to load a file larger than this into a tab. */
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_DIR_ENTRIES = 2000;

    private final AtomicReference<ApplicationHandle> application = new AtomicReference<>();

    public void bind(ApplicationHandle handle) {
        application.set(handle);
    }

    // ---- DTOs (public + opened to Jackson) ----
    public record OpenRequest() {
    }

    public record PathRequest(String path) {
    }

    public record SaveRequest(String path, String content) {
    }

    public record SaveAsRequest(String suggestedName, String content) {
    }

    public record JsonRequest(String json) {
    }

    public record JsonResult(String json) {
    }

    public record Ack(boolean ok) {
    }

    public record OpenResult(boolean cancelled, String path, String name, String content) {
    }

    public record SaveResult(boolean ok, boolean cancelled, String path, String name) {
    }

    public record DirEntry(String name, String path, boolean dir) {
    }

    public record DirListing(boolean cancelled, String path, List<DirEntry> entries) {
    }

    // ---- file open / save through native dialogs ----

    @DesktopCommand("notes.open")
    @RequiresCapability("notes:use")
    public CompletionStage<OpenResult> open(OpenRequest request, InvocationContext context) {
        FileDialog.OpenDialog dialog = new FileDialog.OpenDialog(
                "Open note", Optional.empty(),
                List.of(new FileDialog.Filter("Text & Markdown", List.of("txt", "md", "log", "json")),
                        new FileDialog.Filter("All files", List.of())),
                false, false);
        return require().showOpenDialog(dialog).thenApply(result -> {
            Optional<String> chosen = result.path();
            if (chosen.isEmpty()) {
                return new OpenResult(true, "", "", "");
            }
            return readInto(Path.of(chosen.get()));
        });
    }

    @DesktopCommand("notes.readFile")
    @RequiresCapability("notes:use")
    public CompletionStage<OpenResult> readFile(PathRequest request, InvocationContext context) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "readFile requires a path");
        }
        return CompletableFuture.completedFuture(readInto(Path.of(request.path())));
    }

    @DesktopCommand("notes.save")
    @RequiresCapability("notes:use")
    public CompletionStage<SaveResult> save(SaveRequest request, InvocationContext context) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "save requires the current file path");
        }
        Path file = Path.of(request.path());
        write(file, request.content());
        return CompletableFuture.completedFuture(
                new SaveResult(true, false, file.toString(), fileName(file)));
    }

    @DesktopCommand("notes.saveAs")
    @RequiresCapability("notes:use")
    public CompletionStage<SaveResult> saveAs(SaveAsRequest request, InvocationContext context) {
        String suggested = request == null || request.suggestedName() == null
                || request.suggestedName().isBlank() ? "untitled.txt" : request.suggestedName();
        String content = request == null ? "" : request.content();
        FileDialog.SaveDialog dialog = new FileDialog.SaveDialog(
                "Save note as", Optional.empty(), Optional.of(suggested),
                List.of(new FileDialog.Filter("Text", List.of("txt")),
                        new FileDialog.Filter("Markdown", List.of("md"))));
        return require().showSaveDialog(dialog).thenApply(result -> {
            Optional<String> chosen = result.path();
            if (chosen.isEmpty()) {
                return new SaveResult(false, true, "", "");
            }
            Path file = Path.of(chosen.get());
            write(file, content);
            return new SaveResult(true, false, file.toString(), fileName(file));
        });
    }

    // ---- Files sidebar: folder picker + directory listing ----

    @DesktopCommand("notes.openFolder")
    @RequiresCapability("notes:use")
    public CompletionStage<DirListing> openFolder(OpenRequest request, InvocationContext context) {
        FileDialog.OpenDialog dialog = new FileDialog.OpenDialog(
                "Open folder", Optional.empty(), List.of(), false, true);
        return require().showOpenDialog(dialog).thenApply(result -> {
            Optional<String> chosen = result.path();
            if (chosen.isEmpty()) {
                return new DirListing(true, "", List.of());
            }
            return listing(Path.of(chosen.get()));
        });
    }

    @DesktopCommand("notes.listDir")
    @RequiresCapability("notes:use")
    public CompletionStage<DirListing> listDir(PathRequest request, InvocationContext context) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "listDir requires a path");
        }
        return CompletableFuture.completedFuture(listing(Path.of(request.path())));
    }

    // ---- session persistence (opaque JSON store) ----

    @DesktopCommand("notes.saveSession")
    @RequiresCapability("notes:use")
    public CompletionStage<Ack> saveSession(JsonRequest request, InvocationContext context) {
        String json = request == null || request.json() == null ? "" : request.json();
        try {
            Files.createDirectories(stateDir());
            Files.writeString(sessionFile(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                    "Could not persist session: " + e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(new Ack(true));
    }

    @DesktopCommand("notes.loadSession")
    @RequiresCapability("notes:use")
    public CompletionStage<JsonResult> loadSession(OpenRequest request, InvocationContext context) {
        Path file = sessionFile();
        String json = "";
        try {
            if (Files.isRegularFile(file)) {
                json = Files.readString(file, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                    "Could not read session: " + e.getMessage(), e);
        }
        return CompletableFuture.completedFuture(new JsonResult(json));
    }

    // ---- helpers ----

    private OpenResult readInto(Path file) {
        try {
            if (!Files.isReadable(file)) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST, "File is not readable: " + file);
            }
            long size = Files.size(file);
            if (size > MAX_BYTES) {
                throw new JDeskException(ErrorCode.INVALID_REQUEST,
                        "File is too large to open (" + size + " bytes; limit " + MAX_BYTES + ")");
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return new OpenResult(false, file.toString(), fileName(file), content);
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                    "Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    private DirListing listing(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "Not a folder: " + dir);
        }
        List<DirEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.limit(MAX_DIR_ENTRIES).forEach(p -> {
                boolean isDir = Files.isDirectory(p);
                entries.add(new DirEntry(fileName(p), p.toString(), isDir));
            });
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                    "Could not list " + dir + ": " + e.getMessage(), e);
        }
        entries.sort(Comparator.comparing((DirEntry e) -> !e.dir())
                .thenComparing(e -> e.name().toLowerCase(java.util.Locale.ROOT)));
        return new DirListing(false, dir.toString(), entries);
    }

    private void write(Path file, String content) {
        try {
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                    "Could not write " + file + ": " + e.getMessage(), e);
        }
    }

    private ApplicationHandle require() {
        ApplicationHandle handle = application.get();
        if (handle == null) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE, "Application is not ready yet");
        }
        return handle;
    }

    private static Path stateDir() {
        String override = System.getProperty("jdesk.state.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".jdesk", "dev.jdesk.examples.notes");
    }

    private static Path sessionFile() {
        return stateDir().resolve("session.json");
    }

    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }
}
