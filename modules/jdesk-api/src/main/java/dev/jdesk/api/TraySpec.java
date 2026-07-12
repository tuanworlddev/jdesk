package dev.jdesk.api;

import java.util.Objects;
import java.util.Optional;

/**
 * A system tray / status-bar item (see {@link ApplicationHandle#createTrayItem}).
 *
 * @param title text shown in the status bar (may be empty when an icon is set)
 * @param iconPng optional PNG icon bytes (rendered as a template image on macOS)
 * @param menu menu shown when the item is clicked
 */
public record TraySpec(String title, Optional<byte[]> iconPng, MenuSpec menu) {
    public TraySpec {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(iconPng, "iconPng");
        Objects.requireNonNull(menu, "menu");
    }

    public static TraySpec of(String title, MenuSpec menu) {
        return new TraySpec(title, Optional.empty(), menu);
    }

    public TraySpec withIcon(byte[] pngData) {
        return new TraySpec(title, Optional.of(pngData), menu);
    }
}
