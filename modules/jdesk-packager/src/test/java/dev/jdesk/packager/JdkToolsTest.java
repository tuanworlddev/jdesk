package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkToolsTest {

    @Test
    void locatesPlainAndExeTools(@TempDir Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/jlink"), "");
        Files.writeString(home.resolve("bin/jpackage.exe"), "");

        assertThat(JdkTools.locate(home, "jlink")).isEqualTo(home.resolve("bin/jlink"));
        assertThat(JdkTools.locate(home, "jpackage")).isEqualTo(home.resolve("bin/jpackage.exe"));
        assertThat(JdkTools.locateOrNull(home, "jdeps")).isNull();
    }

    @Test
    void missingToolFailsWithRemediation(@TempDir Path home) {
        assertThatIllegalStateException()
                .isThrownBy(() -> JdkTools.locate(home, "jlink"))
                .withMessageContaining("jlink")
                .withMessageContaining("JDK");
    }

    @Test
    void currentJdkShipsTheRealTools() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        assertThat(JdkTools.locateOrNull(javaHome, "java")).isNotNull();
        assertThat(JdkTools.locateOrNull(javaHome, "jlink")).isNotNull();
        assertThat(JdkTools.locateOrNull(javaHome, "jdeps")).isNotNull();
        assertThat(JdkTools.locateOrNull(javaHome, "jpackage")).isNotNull();
    }
}
