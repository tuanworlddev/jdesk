package dev.jdesk.webview.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jdesk.api.ErrorCode;
import dev.jdesk.api.JDeskException;
import dev.jdesk.api.PrintJob;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CupsPrintingTest {

    @TempDir
    Path dir;

    @Test
    void buildCommandMapsAllOptionsDeterministically() {
        // buildCommand absolutizes the file argument for lp; compute the expected path the
        // same way so the option-mapping assertions stay platform-independent (on Windows an
        // absolutized "/tmp/a.pdf" gains a drive and backslashes).
        String fileA = Path.of("/tmp/a.pdf").toAbsolutePath().toString();
        String fileB = Path.of("/tmp/b.pdf").toAbsolutePath().toString();
        // Full option set — covers every branch without spawning lp (portable).
        assertThat(CupsPrinting.buildCommand(PrintJob.of("/tmp/a.pdf")
                .toPrinter("Zebra").withCopies(3).withPaperSize("Custom.4x6in")))
                .containsSubsequence("lp", "-d", "Zebra", "-n", "3", "-o",
                        "media=Custom.4x6in", fileA);
        // Defaults — no printer, single copy, no media: just lp + file.
        assertThat(CupsPrinting.buildCommand(PrintJob.of("/tmp/b.pdf")))
                .containsExactly("lp", fileB);
    }

    @Test
    void missingFileIsRejectedBeforeSpooling() {
        assertThatThrownBy(() -> CupsPrinting.printFile(PrintJob.of("/no/such/file.pdf")))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void realFileToBogusPrinterReachesTheSpoolerAndFails() throws Exception {
        // Exercises command building + process handling. Either lp is present and
        // rejects the unknown printer (INTERNAL_ERROR), or lp is absent (ILLEGAL_STATE);
        // both are JDeskExceptions — the job never silently succeeds.
        Path pdf = dir.resolve("label.pdf");
        Files.writeString(pdf, "%PDF-1.1\n%%EOF\n");
        assertThatThrownBy(() -> CupsPrinting.printFile(
                PrintJob.of(pdf.toString())
                        .toPrinter("jdesk-nonexistent-printer-xyz")
                        .withCopies(2)
                        .withPaperSize("A4")))
                .isInstanceOf(JDeskException.class);
    }
}
