package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JlinkArgumentsTest {

    @Test
    void buildsFullArgumentListWithSortedDeduplicatedModules() {
        List<String> args = JlinkArguments.builder()
                .modules(Set.of("java.net.http", "java.base"))
                .modules(List.of("java.base"))
                .output(Path.of("build", "jdesk", "runtime image"))
                .addOption("--enable-native-access=ALL-UNNAMED")
                .build()
                .toArguments();

        assertThat(args).containsExactly(
                "--add-modules", "java.base,java.net.http",
                "--output", Path.of("build", "jdesk", "runtime image").toString(),
                "--no-header-files",
                "--no-man-pages",
                "--add-options=--enable-native-access=ALL-UNNAMED");
    }

    @Test
    void omitsAddOptionsWhenNoneConfigured() {
        List<String> args = JlinkArguments.builder()
                .modules(List.of("java.base"))
                .output(Path.of("out"))
                .noHeaderFiles(false)
                .noManPages(false)
                .build()
                .toArguments();

        assertThat(args).containsExactly("--add-modules", "java.base", "--output", "out");
    }

    @Test
    void joinsMultipleAddOptionsWithSpaces() {
        List<String> args = JlinkArguments.builder()
                .modules(List.of("java.base"))
                .output(Path.of("out"))
                .addOption("--enable-native-access=ALL-UNNAMED")
                .addOption("-Djdesk.packaged=true")
                .build()
                .toArguments();

        assertThat(args).contains(
                "--add-options=--enable-native-access=ALL-UNNAMED -Djdesk.packaged=true");
    }

    @Test
    void requiresModules() {
        JlinkArguments args = JlinkArguments.builder().output(Path.of("out")).build();
        assertThatIllegalStateException()
                .isThrownBy(args::toArguments)
                .withMessageContaining("java.base");
    }
}
