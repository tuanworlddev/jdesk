package dev.jdesk.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A request to send a document file (typically a PDF) straight to a printer, bypassing
 * a preview app. Delivered through {@link ApplicationHandle#printFile(PrintJob)}.
 *
 * @param filePath absolute path to the document to print
 * @param printerName target printer, or empty for the system default
 * @param copies number of copies (1..99)
 * @param paperSize media name understood by the print system, e.g. {@code "A4"},
 *        {@code "Letter"}, {@code "Custom.4x6in"}; empty for the printer default
 */
public record PrintJob(String filePath, Optional<String> printerName, int copies,
        Optional<String> paperSize) {
    public PrintJob {
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(printerName, "printerName");
        Objects.requireNonNull(paperSize, "paperSize");
        if (copies < 1 || copies > 99) {
            throw new JDeskException(ErrorCode.INVALID_REQUEST, "copies must be 1..99");
        }
    }

    public static PrintJob of(String filePath) {
        return new PrintJob(filePath, Optional.empty(), 1, Optional.empty());
    }

    public PrintJob toPrinter(String printerName) {
        return new PrintJob(filePath, Optional.of(printerName), copies, paperSize);
    }

    public PrintJob withPaperSize(String paperSize) {
        return new PrintJob(filePath, printerName, copies, Optional.of(paperSize));
    }

    public PrintJob withCopies(int copies) {
        return new PrintJob(filePath, printerName, copies, paperSize);
    }
}
