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
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Release artifact hygiene (spec sections 12.5, 16): SHA-256 checksums and a minimal
 * CycloneDX-style SBOM for the redistributed artifacts. Signing is applied by the OS
 * toolchains via the plugin's signing hooks; unsigned CI artifacts are labeled
 * {@code UNSIGNED} and never satisfy a signed-release gate.
 */
public final class ReleaseArtifacts {
    private static final Pattern JAR_NAME =
            Pattern.compile("(.+)-([0-9][0-9A-Za-z.+-]*)\\.jar");
    private ReleaseArtifacts() {
    }

    /** One checksummed artifact. */
    public record Checksum(String relativePath, long sizeBytes, String sha256) {
    }

    /** One bundled software library discovered from a runtime JAR. */
    public record SoftwareComponent(String name, String version, String purl, String sha256) {
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
     * Writes a CycloneDX 1.7 JSON SBOM listing the application plus the
     * checksummed artifacts as components. Deterministic: no timestamps or random ids
     * (a stable serial number derived from the application id and version is used).
     */
    public static void writeSbom(Path sbomFile, String applicationId, String version,
            List<Checksum> artifacts) {
        writeSbom(sbomFile, applicationId, version, artifacts, List.of());
    }

    /**
     * Writes CycloneDX 1.7 inventory including runtime libraries and an explicit
     * dependency graph. Composition remains marked incomplete because jlink/JDK internals
     * are not Maven components and must not be presented as a complete dependency graph.
     */
    public static void writeSbom(Path sbomFile, String applicationId, String version,
            List<Checksum> artifacts, List<SoftwareComponent> libraries) {
        String serial = "urn:uuid:" + stableUuid(applicationId + "@" + version);
        String appRef = "application:" + applicationId + "@" + version;
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"$schema\": \"https://cyclonedx.org/schema/bom-1.7.schema.json\",\n");
        json.append("  \"bomFormat\": \"CycloneDX\",\n");
        json.append("  \"specVersion\": \"1.7\",\n");
        json.append("  \"serialNumber\": \"").append(serial).append("\",\n");
        json.append("  \"version\": 1,\n");
        json.append("  \"metadata\": {\n");
        json.append("    \"component\": {\n");
        json.append("      \"type\": \"application\",\n");
        json.append("      \"bom-ref\": \"").append(escape(appRef)).append("\",\n");
        json.append("      \"name\": \"").append(escape(applicationId)).append("\",\n");
        json.append("      \"version\": \"").append(escape(version)).append("\",\n");
        json.append("      \"purl\": \"pkg:generic/").append(escape(applicationId))
                .append("@").append(escape(version)).append("\"\n");
        json.append("    }\n");
        json.append("  },\n");
        json.append("  \"components\": [\n");
        int componentCount = artifacts.size() + libraries.size();
        int componentIndex = 0;
        for (Checksum artifact : artifacts) {
            json.append("    {\n");
            json.append("      \"type\": \"file\",\n");
            json.append("      \"bom-ref\": \"file:")
                    .append(escape(artifact.relativePath())).append("\",\n");
            json.append("      \"name\": \"").append(escape(artifact.relativePath())).append("\",\n");
            json.append("      \"hashes\": [ { \"alg\": \"SHA-256\", \"content\": \"")
                    .append(artifact.sha256()).append("\" } ],\n");
            json.append("      \"properties\": [ { \"name\": \"jdesk:sizeBytes\", \"value\": \"")
                    .append(artifact.sizeBytes()).append("\" } ]\n");
            json.append(++componentIndex < componentCount ? "    },\n" : "    }\n");
        }
        for (SoftwareComponent library : libraries.stream()
                .sorted(java.util.Comparator.comparing(SoftwareComponent::purl)).toList()) {
            json.append("    {\n");
            json.append("      \"type\": \"library\",\n");
            json.append("      \"bom-ref\": \"").append(escape(library.purl())).append("\",\n");
            json.append("      \"name\": \"").append(escape(library.name())).append("\",\n");
            json.append("      \"version\": \"").append(escape(library.version())).append("\",\n");
            json.append("      \"purl\": \"").append(escape(library.purl())).append("\",\n");
            json.append("      \"hashes\": [ { \"alg\": \"SHA-256\", \"content\": \"")
                    .append(library.sha256()).append("\" } ]\n");
            json.append(++componentIndex < componentCount ? "    },\n" : "    }\n");
        }
        json.append("  ],\n");
        json.append("  \"dependencies\": [\n");
        json.append("    { \"ref\": \"").append(escape(appRef)).append("\", \"dependsOn\": [");
        for (int i = 0; i < libraries.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append("\"").append(escape(libraries.get(i).purl())).append("\"");
        }
        json.append("] }\n");
        json.append("  ],\n");
        json.append("  \"compositions\": [ { \"aggregate\": \"incomplete\", \"assemblies\": [\"")
                .append(escape(appRef)).append("\"] } ]\n");
        json.append("}\n");
        try {
            Files.writeString(sbomFile, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes an SPDX 2.3 JSON SBOM alongside the CycloneDX one. The EU Cyber Resilience Act
     * makes a machine-readable SBOM binding from Sept 2026, and consumers/scanners differ on
     * which format they ingest — emitting both maximises compatibility. Lists the application
     * package, each redistributed file, and the runtime libraries, related by {@code DEPENDS_ON}
     * / {@code CONTAINS}. {@code created} is an ISO-8601 UTC instant (pass a fixed value, e.g.
     * from {@code SOURCE_DATE_EPOCH}, for a reproducible build).
     */
    public static void writeSpdxSbom(Path sbomFile, String applicationId, String version,
            List<Checksum> artifacts, List<SoftwareComponent> libraries, String created) {
        String docName = applicationId + "-" + version;
        String namespace = "https://spdx.org/spdxdocs/" + docName + "-"
                + stableUuid(applicationId + "@" + version);
        List<SoftwareComponent> libs = libraries.stream()
                .sorted(java.util.Comparator.comparing(SoftwareComponent::purl)).toList();
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"spdxVersion\": \"SPDX-2.3\",\n");
        json.append("  \"dataLicense\": \"CC0-1.0\",\n");
        json.append("  \"SPDXID\": \"SPDXRef-DOCUMENT\",\n");
        json.append("  \"name\": \"").append(escape(docName)).append("\",\n");
        json.append("  \"documentNamespace\": \"").append(escape(namespace)).append("\",\n");
        json.append("  \"creationInfo\": {\n");
        json.append("    \"created\": \"").append(escape(created)).append("\",\n");
        json.append("    \"creators\": [ \"Tool: jdesk-packager\" ]\n");
        json.append("  },\n");

        json.append("  \"packages\": [\n");
        json.append("    {\n");
        json.append("      \"SPDXID\": \"SPDXRef-Application\",\n");
        json.append("      \"name\": \"").append(escape(applicationId)).append("\",\n");
        json.append("      \"versionInfo\": \"").append(escape(version)).append("\",\n");
        json.append("      \"downloadLocation\": \"NOASSERTION\",\n");
        json.append("      \"filesAnalyzed\": false,\n");
        json.append("      \"licenseConcluded\": \"NOASSERTION\",\n");
        json.append("      \"licenseDeclared\": \"NOASSERTION\",\n");
        json.append("      \"copyrightText\": \"NOASSERTION\",\n");
        json.append("      \"externalRefs\": [ { \"referenceCategory\": \"PACKAGE-MANAGER\",")
                .append(" \"referenceType\": \"purl\", \"referenceLocator\": \"pkg:generic/")
                .append(escape(applicationId)).append("@").append(escape(version))
                .append("\" } ]\n");
        json.append(libs.isEmpty() ? "    }\n" : "    },\n");
        for (int i = 0; i < libs.size(); i++) {
            SoftwareComponent lib = libs.get(i);
            json.append("    {\n");
            json.append("      \"SPDXID\": \"SPDXRef-Package-").append(i).append("\",\n");
            json.append("      \"name\": \"").append(escape(lib.name())).append("\",\n");
            json.append("      \"versionInfo\": \"").append(escape(lib.version())).append("\",\n");
            json.append("      \"downloadLocation\": \"NOASSERTION\",\n");
            json.append("      \"filesAnalyzed\": false,\n");
            json.append("      \"licenseConcluded\": \"NOASSERTION\",\n");
            json.append("      \"licenseDeclared\": \"NOASSERTION\",\n");
            json.append("      \"copyrightText\": \"NOASSERTION\",\n");
            json.append("      \"checksums\": [ { \"algorithm\": \"SHA256\", \"checksumValue\": \"")
                    .append(lib.sha256()).append("\" } ],\n");
            json.append("      \"externalRefs\": [ { \"referenceCategory\": \"PACKAGE-MANAGER\",")
                    .append(" \"referenceType\": \"purl\", \"referenceLocator\": \"")
                    .append(escape(lib.purl())).append("\" } ]\n");
            json.append(i + 1 < libs.size() ? "    },\n" : "    }\n");
        }
        json.append("  ],\n");

        json.append("  \"files\": [\n");
        for (int i = 0; i < artifacts.size(); i++) {
            Checksum artifact = artifacts.get(i);
            json.append("    {\n");
            json.append("      \"SPDXID\": \"SPDXRef-File-").append(i).append("\",\n");
            json.append("      \"fileName\": \"").append(escape(artifact.relativePath()))
                    .append("\",\n");
            json.append("      \"checksums\": [ { \"algorithm\": \"SHA256\", \"checksumValue\": \"")
                    .append(artifact.sha256()).append("\" } ]\n");
            json.append(i + 1 < artifacts.size() ? "    },\n" : "    }\n");
        }
        json.append("  ],\n");

        json.append("  \"relationships\": [\n");
        json.append("    { \"spdxElementId\": \"SPDXRef-DOCUMENT\", \"relationshipType\":")
                .append(" \"DESCRIBES\", \"relatedSpdxElement\": \"SPDXRef-Application\" }");
        for (int i = 0; i < libs.size(); i++) {
            json.append(",\n    { \"spdxElementId\": \"SPDXRef-Application\", \"relationshipType\":")
                    .append(" \"DEPENDS_ON\", \"relatedSpdxElement\": \"SPDXRef-Package-")
                    .append(i).append("\" }");
        }
        for (int i = 0; i < artifacts.size(); i++) {
            json.append(",\n    { \"spdxElementId\": \"SPDXRef-Application\", \"relationshipType\":")
                    .append(" \"CONTAINS\", \"relatedSpdxElement\": \"SPDXRef-File-")
                    .append(i).append("\" }");
        }
        json.append("\n  ]\n");
        json.append("}\n");
        try {
            Files.writeString(sbomFile, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads stable component identity and hashes from runtime JARs. */
    public static List<SoftwareComponent> inspectJars(List<Path> jars) {
        List<SoftwareComponent> components = new ArrayList<>();
        for (Path jar : jars.stream().filter(Files::isRegularFile).sorted().toList()) {
            String fileName = jar.getFileName().toString();
            String name = fileName.endsWith(".jar")
                    ? fileName.substring(0, fileName.length() - 4) : fileName;
            String version = "unknown";
            try (JarFile file = new JarFile(jar.toFile())) {
                if (file.getManifest() != null) {
                    var attributes = file.getManifest().getMainAttributes();
                    String moduleName = attributes.getValue("Automatic-Module-Name");
                    String title = attributes.getValue("Implementation-Title");
                    String implementationVersion = attributes.getValue("Implementation-Version");
                    if (moduleName != null && !moduleName.isBlank()) {
                        name = moduleName;
                    } else if (title != null && !title.isBlank()) {
                        name = title;
                    }
                    if (implementationVersion != null && !implementationVersion.isBlank()) {
                        version = implementationVersion;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not inspect runtime JAR " + jar, e);
            }
            if ("unknown".equals(version)) {
                Matcher matcher = JAR_NAME.matcher(fileName);
                if (matcher.matches()) {
                    name = matcher.group(1);
                    version = matcher.group(2);
                }
            }
            String purl = "pkg:generic/" + purl(name) + "@" + purl(version);
            components.add(new SoftwareComponent(name, version, purl, sha256(jar)));
        }
        return List.copyOf(components);
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
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String purl(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int c = Byte.toUnsignedInt(item);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || "-._~".indexOf(c) >= 0) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return encoded.toString();
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
