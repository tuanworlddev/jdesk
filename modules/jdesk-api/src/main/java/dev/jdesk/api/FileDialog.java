package dev.jdesk.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Requests for OS-native, app-modal file open/save dialogs — the in-process replacement
 * for shelling out to {@code osascript}. The dialogs are modal to the app window and
 * follow the app's appearance (dark mode). Obtain results through
 * {@link ApplicationHandle#showOpenDialog(OpenDialog)} /
 * {@link ApplicationHandle#showSaveDialog(SaveDialog)}.
 */
public final class FileDialog {

    private FileDialog() {
    }

    /**
     * A named set of file extensions offered in the dialog's type filter.
     *
     * @param label human-readable group name, e.g. "Images"
     * @param extensions lower-case extensions without the dot, e.g. {@code ["png","jpg"]};
     *        empty means "all files"
     */
    public record Filter(String label, List<String> extensions) {
        public Filter {
            label = Objects.requireNonNull(label, "label");
            extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
        }
    }

    /**
     * Open-dialog request.
     *
     * @param title dialog title (may be empty for the OS default)
     * @param directory initial directory (absolute path), or empty for the OS default
     * @param filters type filters; empty allows any file
     * @param allowMultiple allow selecting more than one file
     * @param chooseDirectories choose directories instead of files
     */
    public record OpenDialog(String title, Optional<String> directory, List<Filter> filters,
            boolean allowMultiple, boolean chooseDirectories) {
        public OpenDialog {
            title = Objects.requireNonNull(title, "title");
            Objects.requireNonNull(directory, "directory");
            filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        }

        public static OpenDialog ofType(String title, Filter... filters) {
            return new OpenDialog(title, Optional.empty(), List.of(filters), false, false);
        }
    }

    /**
     * Save-dialog request.
     *
     * @param title dialog title (may be empty for the OS default)
     * @param directory initial directory (absolute path), or empty for the OS default
     * @param suggestedName pre-filled file name, or empty
     * @param filters type filters; the first filter's first extension is the default
     */
    public record SaveDialog(String title, Optional<String> directory,
            Optional<String> suggestedName, List<Filter> filters) {
        public SaveDialog {
            title = Objects.requireNonNull(title, "title");
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(suggestedName, "suggestedName");
            filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        }

        public static SaveDialog withName(String title, String suggestedName, Filter... filters) {
            return new SaveDialog(title, Optional.empty(), Optional.of(suggestedName),
                    List.of(filters));
        }
    }
}
