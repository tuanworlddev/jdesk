package dev.jdesk.runtime.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupportBundleTest {
    @TempDir Path temp;

    @Test void createsBoundedRedactedBundleWithoutEnvironmentDump() throws Exception {
        Path log = temp.resolve("application.log");
        Files.writeString(log, "before\nAuthorization: Bearer abc.def.ghi\n"
                + "token=super-secret\npath=" + System.getProperty("user.home") + "/private\n");
        Path bundle = SupportBundle.create(temp.resolve("support.zip"),
                new SupportBundleOptions("dev.example.app", "2.0.0", List.of(log),
                        1024, 2048));

        if (Files.getFileAttributeView(bundle, PosixFileAttributeView.class) != null) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(bundle)))
                    .isEqualTo("rw-------");
        }

        try (ZipFile zip = new ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
            assertThat(zip.getEntry("system.json")).isNotNull();
            String system = new String(zip.getInputStream(zip.getEntry("system.json"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertThat(system).contains("\"applicationId\": \"dev.example.app\"",
                    "\"jdeskVersion\"").doesNotContain("PATH", "HOME");
            String content = new String(zip.getInputStream(zip.getEntry("logs/log-01.txt"))
                    .readAllBytes(), StandardCharsets.UTF_8);
            assertThat(content).contains("Authorization: <redacted>",
                            "token=<redacted>", "<home>")
                    .doesNotContain("super-secret", System.getProperty("user.home"));
        }
    }

    @Test void skipsSymlinkLogsAndRejectsSymlinkTarget() throws Exception {
        Path log = temp.resolve("real.log");
        Files.writeString(log, "content");
        Path link = temp.resolve("link.log");
        Files.createSymbolicLink(link, log);
        Path outputTarget = temp.resolve("target.zip");
        Files.writeString(outputTarget, "not zip");
        Path outputLink = temp.resolve("bundle.zip");
        Files.createSymbolicLink(outputLink, outputTarget);

        assertThatThrownBy(() -> SupportBundle.create(outputLink,
                SupportBundleOptions.defaults("dev.example", "1.0.0", List.of(link))))
                .hasMessage("Support bundle target must be a regular file");
    }
}
