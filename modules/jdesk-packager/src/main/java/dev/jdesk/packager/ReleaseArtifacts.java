package dev.jdesk.packager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Release artifact hygiene (spec sections 12.5, 16): SHA-256 checksums and a minimal
 * CycloneDX-style SBOM for the redistributed artifacts. Signing is applied by the OS
 * toolchains via the plugin's signing hooks; unsigned CI artifacts are labeled
 * {@code UNSIGNED} and never satisfy a signed-release gate.
 */
public final class ReleaseArtifacts {
    private ReleaseArtifacts() {
    }

    /** One checksummed artifact. */
    public record Checksum(String relativePath, long sizeBytes, String sha256) {
    }

    /**
     * Computes SHA-256 for every regular file under {@code root} (recursively) and writes
     * a {@code checksums.sha256} file (GNU coreutils format) next to it.
     *
     * @return the checksum records, sorted by path for determinism
     */
    public static List<Checksum> writeChecksums(Path root, Path checksumFile) {
        List<Checksum> checksums = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(checksumFile))
                    .sorted()
                    .forEach(file -> {
                        String rel = root.relativize(file).toString().replace('\\', '/');
                        checksums.add(new Checksum(rel, sizeOf(file), sha256(file)));
                    });
            StringBuilder out = new StringBuilder();
            for (Checksum checksum : checksums) {
                out.append(checksum.sha256()).append("  ").append(checksum.relativePath())
                        .append('\n');
            }
            Files.writeString(checksumFile, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(checksums);
    }

    /**
     * Writes a minimal CycloneDX 1.5 JSON SBOM listing the application plus the
     * checksummed artifacts as components. Deterministic: no timestamps or random ids
     * (a stable serial number derived from the application id and version is used).
     */
    public static void writeSbom(Path sbomFile, String applicationId, String version,
            List<Checksum> artifacts) {
        String serial = "urn:uuid:" + stableUuid(applicationId + "@" + version);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"bomFormat\": \"CycloneDX\",\n");
        json.append("  \"specVersion\": \"1.5\",\n");
        json.append("  \"serialNumber\": \"").append(serial).append("\",\n");
        json.append("  \"version\": 1,\n");
        json.append("  \"metadata\": {\n");
        json.append("    \"component\": {\n");
        json.append("      \"type\": \"application\",\n");
        json.append("      \"bom-ref\": \"").append(escape(applicationId)).append("\",\n");
        json.append("      \"name\": \"").append(escape(applicationId)).append("\",\n");
        json.append("      \"version\": \"").append(escape(version)).append("\"\n");
        json.append("    }\n");
        json.append("  },\n");
        json.append("  \"components\": [\n");
        for (int i = 0; i < artifacts.size(); i++) {
            Checksum artifact = artifacts.get(i);
            json.append("    {\n");
            json.append("      \"type\": \"file\",\n");
            json.append("      \"name\": \"").append(escape(artifact.relativePath())).append("\",\n");
            json.append("      \"hashes\": [ { \"alg\": \"SHA-256\", \"content\": \"")
                    .append(artifact.sha256()).append("\" } ]\n");
            json.append(i + 1 < artifacts.size() ? "    },\n" : "    }\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        try {
            Files.writeString(sbomFile, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deterministic name-based UUID (RFC 4122 v5-style over SHA-256, truncated). */
    static String stableUuid(String seed) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // variant
        String hex = HexFormat.of().formatHex(hash);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
