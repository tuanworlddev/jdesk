package dev.jdesk.webview.spi;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PrintJob;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sends a document file to a printer via the CUPS {@code lp} command (macOS and Linux).
 * The file path travels as an argument (not stdin) so {@code lp} reads it directly; the
 * job is submitted to the spooler and this returns once {@code lp} accepts it.
 */
public final class CupsPrinting {

    private CupsPrinting() {
    }

    /**
     * The {@code lp} argument vector for a job: {@code -d} printer, {@code -n} copies,
     * {@code -o media=} paper size, then the absolute file path. Pure and deterministic
     * (no I/O), so the option mapping is unit-testable on any platform.
     */
    static List<String> buildCommand(PrintJob job) {
        List<String> command = new ArrayList<>();
        command.add("lp");
        job.printerName().ifPresent(printer -> {
            command.add("-d");
            command.add(printer);
        });
        if (job.copies() > 1) {
            command.add("-n");
            command.add(Integer.toString(job.copies()));
        }
        job.paperSize().ifPresent(media -> {
            command.add("-o");
            command.add("media=" + media);
        });
        command.add(Path.of(job.filePath()).toAbsolutePath().toString());
        return command;
    }

    public static void printFile(PrintJob job) {
        Path file = Path.of(job.filePath());
        if (!Files.isReadable(file)) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST,
                    "Print file does not exist or is not readable");
        }
        List<String> command = buildCommand(job);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new JDeskException(ErrorCode.TIMEOUT, "lp did not accept the job in time");
            }
            if (process.exitValue() != 0) {
                throw new JDeskException(ErrorCode.INTERNAL_ERROR,
                        "lp rejected the print job (exit " + process.exitValue() + ")");
            }
        } catch (IOException e) {
            throw new JDeskException(ErrorCode.ILLEGAL_STATE,
                    "Printing requires the CUPS 'lp' command; it was not found", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JDeskException(ErrorCode.CANCELLED, "Print submission interrupted", e);
        }
    }
}
