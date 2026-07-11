package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JdepsArgumentsTest {

    @Test
    void buildsFullArgumentList() {
        List<String> args = JdepsArguments.builder()
                .multiRelease(25)
                .classPath(List.of(Path.of("libs", "a.jar"), Path.of("libs", "b with space.jar")))
                .roots(List.of(Path.of("app.jar")))
                .build()
                .toArguments();

        assertThat(args).containsExactly(
                "--print-module-deps",
                "--ignore-missing-deps",
                "--multi-release", "25",
                "--class-path",
                Path.of("libs", "a.jar") + File.pathSeparator + Path.of("libs", "b with space.jar"),
                "app.jar");
    }

    @Test
    void omitsOptionalFlags() {
        List<String> args = JdepsArguments.builder()
                .printModuleDeps(false)
                .ignoreMissingDeps(false)
                .multiRelease(0)
                .roots(List.of(Path.of("app.jar")))
                .build()
                .toArguments();

        assertThat(args).containsExactly("app.jar");
    }

    @Test
    void requiresAtLeastOneRoot() {
        JdepsArguments args = JdepsArguments.builder().build();
        assertThatIllegalStateException()
                .isThrownBy(args::toArguments)
                .withMessageContaining("root");
    }
}
