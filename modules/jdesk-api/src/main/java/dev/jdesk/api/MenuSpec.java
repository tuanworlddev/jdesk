package dev.jdesk.api;

import java.util.List;

/**
 * A menu as a list of top-level {@link MenuItem}s, used with
 * {@link ApplicationHandle#setApplicationMenu}. On macOS the first submenu is the
 * application menu; build it with {@link MenuItem#submenu}.
 */
public record MenuSpec(List<MenuItem> items) {
    public MenuSpec {
        items = List.copyOf(items);
    }

    public static MenuSpec of(MenuItem... items) {
        return new MenuSpec(List.of(items));
    }
}
