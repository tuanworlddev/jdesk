package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JpackageArgumentsTest {

    @Test
    void buildsAppImageArgumentList() {
        List<String> args = JpackageArguments.builder()
                .name("hello")
                .input(Path.of("build", "jdesk", "package-input"))
                .mainJar("hello-1.0.jar")
                .mainClass("dev.example.Main")
                .runtimeImage(Path.of("build", "jdesk", "runtime-image"))
                .destination(Path.of("build", "jdesk", "package"))
                .appVersion("1.2.3")
                .javaOption("--enable-native-access=ALL-UNNAMED")
                .javaOption("-XstartOnFirstThread")
                .macPackageIdentifier("dev.example.hello")
                .build()
                .toArguments();

        assertThat(args).containsExactly(
                "--type", "app-image",
                "--name", "hello",
                "--input", Path.of("build", "jdesk", "package-input").toString(),
                "--main-jar", "hello-1.0.jar",
                "--main-class", "dev.example.Main",
                "--runtime-image", Path.of("build", "jdesk", "runtime-image").toString(),
                "--dest", Path.of("build", "jdesk", "package").toString(),
                "--app-version", "1.2.3",
                "--java-options", "--enable-native-access=ALL-UNNAMED",
                "--java-options", "-XstartOnFirstThread",
                "--mac-package-identifier", "dev.example.hello");
    }

    @Test
    void omitsMacIdentifierWhenAbsent() {
        List<String> args = minimal().build().toArguments();
        assertThat(args).doesNotContain("--mac-package-identifier", "--java-options");
    }

    @Test
    void buildsNamedModuleLaunchWithoutClasspathFallback() {
        List<String> args = JpackageArguments.builder()
                .name("hello")
                .modulePath(Path.of("build", "modules"))
                .module("dev.example.hello", "dev.example.Main")
                .runtimeImage(Path.of("build", "runtime"))
                .destination(Path.of("build", "package"))
                .appVersion("1.0.0")
                .javaOption("--enable-native-access=dev.jdesk.platform.linux")
                .javaOption("--illegal-native-access=deny")
                .build()
                .toArguments();

        assertThat(args).containsSubsequence(
                "--module-path", Path.of("build", "modules").toString(),
                "--module", "dev.example.hello/dev.example.Main");
        assertThat(args).contains("--enable-native-access=dev.jdesk.platform.linux",
                "--illegal-native-access=deny");
        assertThat(args).doesNotContain("--input", "--main-jar", "--main-class",
                "--enable-native-access=ALL-UNNAMED");
    }

    @Test
    void requiresMainClass() {
        JpackageArguments.Builder builder = JpackageArguments.builder()
                .name("hello")
                .input(Path.of("in"))
                .mainJar("a.jar")
                .runtimeImage(Path.of("ri"))
                .destination(Path.of("out"))
                .appVersion("1.0");
        assertThatIllegalStateException()
                .isThrownBy(builder::build)
                .withMessageContaining("mainClass");
    }

    @Test
    void normalizesVersions() {
        assertThat(JpackageArguments.normalizeVersion("0.1.0-SNAPSHOT", false)).isEqualTo("0.1.0");
        assertThat(JpackageArguments.normalizeVersion("0.1.0-SNAPSHOT", true)).isEqualTo("1.0.0");
        assertThat(JpackageArguments.normalizeVersion("2.3", true)).isEqualTo("2.3");
        assertThat(JpackageArguments.normalizeVersion("2.3.4", false)).isEqualTo("2.3.4");
        assertThat(JpackageArguments.normalizeVersion("weird", false)).isEqualTo("1.0.0");
        assertThat(JpackageArguments.normalizeVersion("", true)).isEqualTo("1.0.0");
        assertThat(JpackageArguments.normalizeVersion(null, true)).isEqualTo("1.0.0");
    }

    private static JpackageArguments.Builder minimal() {
        return JpackageArguments.builder()
                .name("hello")
                .input(Path.of("in"))
                .mainJar("a.jar")
                .mainClass("dev.example.Main")
                .runtimeImage(Path.of("ri"))
                .destination(Path.of("out"))
                .appVersion("1.0");
    }
}
