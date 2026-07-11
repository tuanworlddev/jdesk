package dev.jdesk.codegen;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles in-test sources with the JDK compiler and the JDesk annotation processor. */
final class TestCompiler {

    private TestCompiler() {
    }

    record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics,
            Path classesDir, Path generatedSourcesDir) {

        String errorText() {
            StringBuilder b = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    b.append(diagnostic.getMessage(Locale.ROOT)).append('\n');
                }
            }
            return b.toString();
        }

        long errorCount() {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
        }

        String generatedSource(String qualifiedName) throws IOException {
            Path file = generatedSourcesDir.resolve(qualifiedName.replace('.', '/') + ".java");
            return Files.readString(file, StandardCharsets.UTF_8);
        }

        boolean generatedSourceExists(String qualifiedName) {
            return Files.exists(
                    generatedSourcesDir.resolve(qualifiedName.replace('.', '/') + ".java"));
        }

        String tsFile(String name) throws IOException {
            return Files.readString(classesDir.resolve("jdesk-ts").resolve(name),
                    StandardCharsets.UTF_8);
        }

        /** All generated .java and .ts files relative to their output roots, sorted. */
        Map<String, byte[]> generatedFiles() throws IOException {
            Map<String, byte[]> files = new TreeMap<>();
            collect(files, generatedSourcesDir, ".java");
            collect(files, classesDir, ".ts");
            return files;
        }

        private static void collect(Map<String, byte[]> files, Path root, String suffix)
                throws IOException {
            if (!Files.isDirectory(root)) {
                return;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path path : stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(suffix)).toList()) {
                    files.put(root.relativize(path).toString().replace('\\', '/'),
                            Files.readAllBytes(path));
                }
            }
        }
    }

    static Result compile(Path workDir, Map<String, String> sources) throws IOException {
        return compile(workDir, sources, List.of());
    }

    /** Compiles the given sources (qualified class name to content) with the processor. */
    static Result compile(Path workDir, Map<String, String> sources, List<String> extraOptions)
            throws IOException {
        Path sourceDir = Files.createDirectories(workDir.resolve("src"));
        Path classesDir = Files.createDirectories(workDir.resolve("classes"));
        Path generatedDir = Files.createDirectories(workDir.resolve("generated"));
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(sources).entrySet()) {
            Path file = sourceDir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            files.add(file);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of(
                    "-classpath", apiClasspath(),
                    "-d", classesDir.toString(),
                    "-s", generatedDir.toString(),
                    "-encoding", "UTF-8"));
            options.addAll(extraOptions);
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                    options, null, fileManager.getJavaFileObjectsFromPaths(files));
            task.setProcessors(List.of(new DesktopCommandProcessor()));
            boolean success = Boolean.TRUE.equals(task.call());
            return new Result(success, diagnostics.getDiagnostics(), classesDir, generatedDir);
        }
    }

    /** Compiles all generated sources of a previous result in a fresh javac invocation. */
    static Result compileGenerated(Path workDir, Result previous) throws IOException {
        Path classesDir = Files.createDirectories(workDir.resolve("classes2"));
        List<Path> files;
        try (Stream<Path> stream = Files.walk(previous.generatedSourcesDir())) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = List.of(
                    "-classpath", apiClasspath() + java.io.File.pathSeparator
                            + previous.classesDir(),
                    "-d", classesDir.toString(),
                    "-encoding", "UTF-8");
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                    options, null, fileManager.getJavaFileObjectsFromPaths(files));
            boolean success = Boolean.TRUE.equals(task.call());
            return new Result(success, diagnostics.getDiagnostics(), classesDir,
                    previous.generatedSourcesDir());
        }
    }

    static String apiClasspath() {
        return locationOf(dev.jdesk.api.DesktopCommand.class);
    }

    static String processorClasspath() {
        return locationOf(DesktopCommandProcessor.class);
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate classpath entry for " + type, e);
        }
    }
}
