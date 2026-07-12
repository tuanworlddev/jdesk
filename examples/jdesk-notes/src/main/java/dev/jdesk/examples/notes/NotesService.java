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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Notes example's feature service: file open/save backed by real OS-native dialogs.
 *
 * <p>Open and Save&nbsp;As invoke {@link ApplicationHandle#showOpenDialog}/{@code showSaveDialog}
 * (the OS-native, app-modal file choosers — comdlg32 {@code IFileDialog} on Windows, NSOpen/
 * SavePanel on macOS, GtkFileChooser on Linux); Save writes straight to the already-known path.
 * All file I/O runs off the UI thread on the command's virtual thread and returns through a
 * {@link CompletionStage}. The {@code jdesk-codegen} annotation processor turns each
 * {@link DesktopCommand} into compile-time registration plus TypeScript bindings.</p>
 */
public class NotesService {

    /** Editor safety cap: refuse to load a file larger than this into the text area. */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    /** The running application's control plane, bound at {@code onReady}. */
    private final AtomicReference<ApplicationHandle> application = new AtomicReference<>();

    /** Called from the lifecycle listener once the app is ready. */
    public void bind(ApplicationHandle handle) {
        application.set(handle);
    }

    // ---- DTOs (public + opened to Jackson for JSON binding) ----

    /** Empty request for the no-argument Open command (page sends {@code {}}). */
    public record OpenRequest() {
    }

    public record SaveRequest(String path, String content) {
    }

    public record SaveAsRequest(String suggestedName, String content) {
    }

    /** Result of Open: {@code cancelled} true means the user dismissed the dialog. */
    public record OpenResult(boolean cancelled, String path, String name, String content) {
    }

    /** Result of Save / Save As: {@code cancelled} only applies to the Save-As dialog. */
    public record SaveResult(boolean ok, boolean cancelled, String path, String name) {
    }

    @DesktopCommand("notes.open")
    @RequiresCapability("notes:use")
    public CompletionStage<OpenResult> open(OpenRequest request, InvocationContext context) {
        FileDialog.OpenDialog dialog = new FileDialog.OpenDialog(
                "Open note", Optional.empty(),
                List.of(new FileDialog.Filter("Text & Markdown", List.of("txt", "md", "log")),
                        new FileDialog.Filter("All files", List.of())),
                false, false);
        return require().showOpenDialog(dialog).thenApply(result -> {
            Optional<String> chosen = result.path();
            if (chosen.isEmpty()) {
                return new OpenResult(true, "", "", "");
            }
            Path file = Path.of(chosen.get());
            try {
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
        });
    }

    @DesktopCommand("notes.save")
    @RequiresCapability("notes:use")
    public CompletionStage<SaveResult> save(SaveRequest request, InvocationContext context) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "save requires the current file path");
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

    private static String fileName(Path file) {
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }
}
