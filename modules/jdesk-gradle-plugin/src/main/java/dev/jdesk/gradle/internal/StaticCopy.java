package dev.jdesk.gradle.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Recursively copies a static frontend tree into the dist directory, preserving structure
 * so {@code /src/...} paths resolve, and excluding {@code node_modules}, {@code .git} and
 * the dist directory itself. Shared by {@code jdeskFrontendBuild} (production copy) and
 * {@code jdeskDev} (rebuild-on-change) so a no-bundler app needs no {@code Build.java}.
 */
public final class StaticCopy {

    private StaticCopy() {
    }

    public static void copy(Path source, Path dist) throws IOException {
        deleteRecursively(dist);
        Files.createDirectories(dist);
        try (Stream<Path> tree = Files.walk(source)) {
            tree.forEach(path -> {
                Path relative = source.relativize(path);
                String top = relative.getNameCount() == 0 ? "" : relative.getName(0).toString();
                if (top.equals("node_modules") || top.equals(".git") || path.startsWith(dist)) {
                    return;
                }
                try {
                    Path target = dist.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("static copy failed at " + path, e);
                }
            });
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(dir)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // Best-effort clean; a leftover file is overwritten by the copy.
                }
            });
        }
    }
}
