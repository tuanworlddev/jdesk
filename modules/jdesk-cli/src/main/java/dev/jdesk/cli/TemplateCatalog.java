package dev.jdesk.cli;

import java.util.ArrayList;
import java.util.List;

final class TemplateCatalog {
    // The single-module templates are classpath (non-modular) apps: no module-info.java,
    // so beginners don't deal with JPMS. The structured template keeps JPMS.
    private static final List<String> BASIC = List.of(
            "gitignore", "README.md", "settings.gradle.kts", "build.gradle.kts",
            "src/main/java/@PACKAGE_PATH@/Main.java",
            "src/main/java/@PACKAGE_PATH@/GreetingService.java",
            "src/main/resources/jdesk-capabilities.json",
            "ui/index.html", "ui/src/main.js", "ui/src/style.css");
    private static final List<String> BASIC_CORE = List.of(
            "gitignore", "README.md", "settings.gradle.kts", "build.gradle.kts",
            "src/main/java/@PACKAGE_PATH@/Main.java",
            "src/main/java/@PACKAGE_PATH@/GreetingService.java",
            "src/main/resources/jdesk-capabilities.json");
    private static final List<String> FRAMEWORK_UI = List.of(
            "ui/package.json", "ui/index.html", "ui/src/main.js", "ui/src/style.css");
    private static final List<String> STRUCTURED = List.of(
            "gitignore", "README.md", "settings.gradle.kts", "build.gradle.kts",
            "domain/build.gradle.kts", "domain/src/main/java/module-info.java",
            "domain/src/main/java/@PACKAGE_PATH@/domain/Greeting.java",
            "application/build.gradle.kts", "application/src/main/java/module-info.java",
            "application/src/main/java/@PACKAGE_PATH@/application/GreetingUseCase.java",
            "infrastructure/build.gradle.kts", "infrastructure/src/main/java/module-info.java",
            "infrastructure/src/main/java/@PACKAGE_PATH@/infrastructure/SystemGreetingUseCase.java",
            "desktop/build.gradle.kts", "desktop/src/main/java/module-info.java",
            "desktop/src/main/java/@PACKAGE_PATH@/desktop/Main.java",
            "desktop/src/main/java/@PACKAGE_PATH@/desktop/GreetingCommands.java",
            "desktop/src/main/resources/jdesk-capabilities.json",
            "ui/index.html", "ui/src/main.js", "ui/src/style.css");

    private TemplateCatalog() {
    }

    static List<TemplateFile> files(String template) {
        if ("structured".equals(template)) {
            return direct("structured", STRUCTURED);
        }
        if ("basic".equals(template)) {
            return direct("basic", BASIC);
        }
        if ("maven".equals(template)) {
            List<TemplateFile> files = new ArrayList<>(direct("basic", BASIC));
            files.removeIf(file -> file.output().equals("build.gradle.kts")
                    || file.output().equals("settings.gradle.kts"));
            files.add(new TemplateFile("maven/pom.xml", "pom.xml"));
            return List.copyOf(files);
        }
        List<TemplateFile> files = new ArrayList<>(direct("basic", BASIC_CORE));
        // Framework projects need npm/Vite for both dev and production builds.
        files.removeIf(file -> "build.gradle.kts".equals(file.output()));
        files.add(new TemplateFile("framework/build.gradle.kts", "build.gradle.kts"));
        files.addAll(direct(template, FRAMEWORK_UI));
        if ("svelte".equals(template)) {
            files.add(new TemplateFile("svelte/ui/src/App.svelte", "ui/src/App.svelte"));
            files.add(new TemplateFile("svelte/ui/vite.config.js", "ui/vite.config.js"));
        }
        return List.copyOf(files);
    }

    private static List<TemplateFile> direct(String resourceRoot, List<String> paths) {
        return paths.stream().map(path -> new TemplateFile(resourceRoot + "/" + path, path)).toList();
    }

    record TemplateFile(String resource, String output) {
    }
}
