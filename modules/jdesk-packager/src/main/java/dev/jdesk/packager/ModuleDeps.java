package dev.jdesk.packager;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Parses {@code jdeps --print-module-deps} output into a sorted module set. */
public final class ModuleDeps {
    private static final Pattern MODULE_LIST =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.]*(,[A-Za-z][A-Za-z0-9_.]*)*");

    private ModuleDeps() {
    }

    /**
     * Extracts the module list from jdeps stdout. jdeps prints the comma-separated
     * module list as the last non-blank line; earlier lines (e.g. split-package
     * warnings) are ignored.
     *
     * @return sorted set of module names; empty when stdout contains no module list
     * @throws IllegalArgumentException when the last line is not a module list
     */
    public static Set<String> parse(String jdepsStdout) {
        String last = null;
        for (String line : jdepsStdout.split("\\R")) {
            if (!line.isBlank()) {
                last = line.strip();
            }
        }
        if (last == null) {
            return Set.of();
        }
        if (!MODULE_LIST.matcher(last).matches()) {
            throw new IllegalArgumentException(
                    "Unexpected jdeps --print-module-deps output (last line: '" + last
                            + "'). Full output:\n" + jdepsStdout);
        }
        Set<String> modules = new TreeSet<>();
        for (String module : last.split(",")) {
            modules.add(module.strip());
        }
        return modules;
    }
}
