package dev.jdesk.packager;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseArtifactsTest {

    @TempDir
    Path dir;

    @Test
    void checksumsCoverEveryFileAndAreVerifiable() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello");
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/b.txt"), "world");
        Path checksumFile = dir.resolve("checksums.sha256");

        List<ReleaseArtifacts.Checksum> checksums =
                ReleaseArtifacts.writeChecksums(dir, checksumFile);

        assertThat(checksums).extracting(ReleaseArtifacts.Checksum::relativePath)
                .containsExactly("a.txt", "sub/b.txt");
        // Known SHA-256 of "hello".
        assertThat(checksums.getFirst().sha256())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        // The checksum file lists both entries and excludes itself.
        String content = Files.readString(checksumFile);
        assertThat(content).contains("  a.txt").contains("  sub/b.txt")
                .doesNotContain("checksums.sha256");
    }

    @Test
    void checksumsAreDeterministicAcrossRuns() throws Exception {
        Files.writeString(dir.resolve("x.bin"), "content");
        Path first = dir.resolve("c1.sha256");
        Path second = dir.resolve("c2.sha256");
        ReleaseArtifacts.writeChecksums(dir, first);
        // Second run sees the first checksum file; exclude it by pointing at a fresh name
        // and deleting the prior artifact to keep inputs identical.
        Files.delete(first);
        ReleaseArtifacts.writeChecksums(dir, second);
        // Re-run against the original name after removing the second.
        Files.delete(second);
        List<ReleaseArtifacts.Checksum> a = ReleaseArtifacts.writeChecksums(dir, first);
        Files.delete(first);
        List<ReleaseArtifacts.Checksum> b = ReleaseArtifacts.writeChecksums(dir, first);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void sbomIsValidCycloneDxAndDeterministic() throws Exception {
        Files.writeString(dir.resolve("app.jar"), "jarbytes");
        Path checksumFile = dir.resolve("checksums.sha256");
        List<ReleaseArtifacts.Checksum> artifacts =
                ReleaseArtifacts.writeChecksums(dir, checksumFile);

        Path sbom1 = dir.resolve("sbom1.json");
        Path sbom2 = dir.resolve("sbom2.json");
        ReleaseArtifacts.writeSbom(sbom1, "dev.example.app", "1.2.3", artifacts);
        ReleaseArtifacts.writeSbom(sbom2, "dev.example.app", "1.2.3", artifacts);

        String json = Files.readString(sbom1, StandardCharsets.UTF_8);
        assertThat(json).contains("\"bomFormat\": \"CycloneDX\"")
                .contains("\"specVersion\": \"1.5\"")
                .contains("\"name\": \"dev.example.app\"")
                .contains("\"version\": \"1.2.3\"")
                .contains("\"alg\": \"SHA-256\"")
                .contains("app.jar");
        assertThat(Files.readString(sbom2)).isEqualTo(json); // deterministic
    }

    @Test
    void stableUuidIsSeedDeterministic() {
        assertThat(ReleaseArtifacts.stableUuid("dev.example.app@1.0.0"))
                .isEqualTo(ReleaseArtifacts.stableUuid("dev.example.app@1.0.0"))
                .isNotEqualTo(ReleaseArtifacts.stableUuid("dev.example.app@1.0.1"))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }
}
