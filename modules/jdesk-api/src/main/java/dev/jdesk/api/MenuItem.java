package dev.jdesk.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry in an application or context menu (see {@link MenuSpec}). An {@link Action} is a
 * clickable item that reports its {@code id} to the menu's action listener; a {@link Submenu}
 * nests items; a {@link Separator} draws a divider.
 */
public sealed interface MenuItem permits MenuItem.Action, MenuItem.Submenu, MenuItem.Separator {

    /**
     * @param id stable identifier delivered to the action listener when clicked
     * @param label display text
     * @param accelerator optional shortcut such as {@code "CmdOrCtrl+S"} ({@code Cmd} on
     *        macOS, {@code Ctrl} elsewhere); modifiers {@code Cmd}/{@code Ctrl}/{@code Alt}/
     *        {@code Shift} joined with {@code +}
     * @param checked draws a checkmark next to the item (for toggle/option items)
     * @param enabled when false the item is greyed out and cannot be chosen
     */
    record Action(String id, String label, Optional<String> accelerator, boolean checked,
            boolean enabled) implements MenuItem {
        public Action {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(accelerator, "accelerator");
        }

        /** Returns a copy with the checkmark set to {@code value}. */
        public Action checked(boolean value) {
            return new Action(id, label, accelerator, value, enabled);
        }

        /** Returns a copy with the enabled state set to {@code value}. */
        public Action enabled(boolean value) {
            return new Action(id, label, accelerator, checked, value);
        }
    }

    record Submenu(String label, List<MenuItem> items) implements MenuItem {
        public Submenu {
            Objects.requireNonNull(label, "label");
            items = List.copyOf(items);
        }
    }

    record Separator() implements MenuItem {
    }

    static Action action(String id, String label) {
        return new Action(id, label, Optional.empty(), false, true);
    }

    static Action action(String id, String label, String accelerator) {
        return new Action(id, label, Optional.of(accelerator), false, true);
    }

    /** A checkable item that shows a checkmark when {@code checked}. */
    static Action check(String id, String label, boolean checked) {
        return new Action(id, label, Optional.empty(), checked, true);
    }

    static Submenu submenu(String label, MenuItem... items) {
        return new Submenu(label, List.of(items));
    }

    static Separator separator() {
        return new Separator();
    }
}
