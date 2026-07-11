package dev.jdesk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Validation and shape of the file-dialog and print API records. */
class FileDialogAndPrintTest {

    @Test
    void openDialogFactoryAndDefensiveCopies() {
        FileDialog.Filter images = new FileDialog.Filter("Images", List.of("png", "jpg"));
        FileDialog.OpenDialog dialog = FileDialog.OpenDialog.ofType("Pick", images);
        assertThat(dialog.filters()).containsExactly(images);
        assertThat(dialog.allowMultiple()).isFalse();
        assertThat(dialog.chooseDirectories()).isFalse();
        assertThat(dialog.directory()).isEmpty();
    }

    @Test
    void saveDialogWithNameCarriesSuggestion() {
        FileDialog.SaveDialog dialog = FileDialog.SaveDialog.withName("Save", "report.pdf",
                new FileDialog.Filter("PDF", List.of("pdf")));
        assertThat(dialog.suggestedName()).contains("report.pdf");
        assertThat(dialog.filters()).hasSize(1);
    }

    @Test
    void fileDialogResultCancelledAndPathAccessors() {
        assertThat(FileDialogResult.cancelled().isCancelled()).isTrue();
        assertThat(FileDialogResult.cancelled().path()).isEmpty();
        FileDialogResult two = new FileDialogResult(List.of("/a", "/b"));
        assertThat(two.isCancelled()).isFalse();
        assertThat(two.path()).contains("/a");
        assertThat(two.paths()).containsExactly("/a", "/b");
    }

    @Test
    void printJobDefaultsAndBuilders() {
        PrintJob job = PrintJob.of("/tmp/label.pdf")
                .toPrinter("Zebra")
                .withCopies(3)
                .withPaperSize("Custom.4x6in");
        assertThat(job.filePath()).isEqualTo("/tmp/label.pdf");
        assertThat(job.printerName()).contains("Zebra");
        assertThat(job.copies()).isEqualTo(3);
        assertThat(job.paperSize()).contains("Custom.4x6in");
    }

    @Test
    void printJobRejectsOutOfRangeCopies() {
        assertThatThrownBy(() -> PrintJob.of("/tmp/x.pdf").withCopies(0))
                .isInstanceOfSatisfying(JDeskException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> new PrintJob("/x", Optional.empty(), 100, Optional.empty()))
                .isInstanceOf(JDeskException.class);
    }
}
