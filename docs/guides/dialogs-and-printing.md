# Native dialogs and printing

JDesk provides in-process, app-modal file dialogs and printing, so you never shell out to
`osascript` (dialogs that belong to another process, aren't modal to your window, and
don't follow your app's dark mode).

## File open/save dialogs

```java
// Save — pre-fill a name and a type filter.
FileDialogResult saved = context.application().showSaveDialog(
        FileDialog.SaveDialog.withName("Export invoice", "invoice-1024.pdf",
            new FileDialog.Filter("PDF", List.of("pdf"))))
        .toCompletableFuture().join();
saved.path().ifPresent(path -> writePdf(path));

// Open — one or many files of a given type.
FileDialogResult picked = context.application().showOpenDialog(
        new FileDialog.OpenDialog("Import images", Optional.empty(),
            List.of(new FileDialog.Filter("Images", List.of("png", "jpg"))),
            /* allowMultiple */ true, /* chooseDirectories */ false))
        .toCompletableFuture().join();
for (String path : picked.paths()) { importImage(path); }
```

`isCancelled()` / an empty `paths()` means the user dismissed the dialog. The dialogs are
modal to the application and follow its appearance.

Platform status: macOS uses `NSOpenPanel`/`NSSavePanel` (live-verified). Windows uses
comdlg32 `GetOpenFileNameW`/`GetSaveFileNameW`; Linux uses `GtkFileChooserDialog` — both
implemented and compile-verified (a modal dialog can't be driven on headless CI).

## Printing the current page

```java
context.application().window(windowId).orElseThrow().print();
```

This opens the OS print dialog for the window's page content (macOS `NSPrintOperation` —
live-verified; Linux `webkit_print_operation_run_dialog` — compile-verified). On Windows
the WebView2 print UI is not yet wired, so `print()` reports "not supported" there.

## Printing a file straight to a printer

For unattended label/receipt printing — pick a printer and paper size, no preview app:

```java
context.application().printFile(
        PrintJob.of("/var/labels/order-1024.pdf")
            .toPrinter("Zebra-ZD410")
            .withPaperSize("Custom.4x6in")
            .withCopies(2))
    .toCompletableFuture().join();
```

Platform status: macOS and Linux submit through the CUPS `lp` command (verified: a job to
a non-existent printer is rejected, proving it reaches the spooler rather than silently
succeeding). Windows uses the ShellExecute `print`/`printto` verb (default vs. named
printer); it honors `copies` by submitting the job that many times, but not `paperSize`
(which needs a full Win32 print pipeline — a documented gap). Printing runs off the UI
thread, so it is safe to call from a command handler.

## Notifications, sharing, and biometrics

`context.application()` exposes a few more OS-integration surfaces:

```java
// Interactive notification — action buttons and an inline reply come back in the response.
InteractiveNotification note = InteractiveNotification.of("Build finished", "3 tests passed")
        .withActions(new InteractiveNotification.Action("open", "Open log"))
        .withReply("Comment");
NotificationResponse response = app.showNotification(note).toCompletableFuture().join();
response.actionId().ifPresent(id -> ...);   // which button
response.replyText().ifPresent(text -> ...); // what the user typed

// OS share sheet for text and/or URLs.
app.share(ShareContent.urls("https://example.com/report.pdf")).toCompletableFuture().join();

// Gate a sensitive action on Touch ID / Windows Hello availability.
if (app.biometricsAvailable().toCompletableFuture().join()) { ... }
```

Platform status: interactive notifications are implemented on macOS via `NSUserNotification`
(action button, an additional-actions menu, and inline reply); the returned stage completes when
the user acts. The share sheet uses `NSSharingServicePicker` and biometrics report
`LAContext canEvaluatePolicy`. As with the other GUI surfaces, the visible popover / notification
click / Touch-ID prompt are verified interactively, not by a unit test. A plain
`showNotification(title, body)` remains for a simple banner.
