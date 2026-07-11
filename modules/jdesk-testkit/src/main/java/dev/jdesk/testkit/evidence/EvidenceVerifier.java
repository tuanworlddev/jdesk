package dev.jdesk.testkit.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Anti-fake evidence verification (spec section 18): recomputes checksums, validates the
 * manifest schema, checks timestamps and status consistency, and rejects fake provider
 * ids for native/package categories. Backs the {@code jdeskVerifyEvidence} task.
 */
public final class EvidenceVerifier {
    private static final Set<String> REQUIRED_FILES = Set.of(
            "manifest.json", "environment.json", "results.json",
            "app.log", "stdout.log", "stderr.log", "checksums.sha256", "junit.xml");
    private static final Set<String> NATIVE_CATEGORIES = Set.of("native", "package", "release");

    private final ObjectMapper mapper = new ObjectMapper();

    /** All problems found for one evidence run directory; empty means valid. */
    public List<String> verify(Path runDir) {
        List<String> problems = new ArrayList<>();
        Path manifestPath = runDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            problems.add("manifest.json missing");
            return problems;
        }
        JsonNode manifest;
        try {
            manifest = mapper.readTree(Files.readString(manifestPath));
        } catch (IOException e) {
            problems.add("manifest.json unreadable: " + e.getMessage());
            return problems;
        }

        for (String field : List.of("schemaVersion", "runId", "startedAtUtc", "gitCommit",
                "osName", "architecture", "jdkVersion", "platformProviderId", "category",
                "command", "status")) {
            if (manifest.get(field) == null || manifest.get(field).isNull()) {
                problems.add("manifest field missing: " + field);
            }
        }
        if (manifest.path("schemaVersion").asInt() != EvidenceRun.SCHEMA_VERSION) {
            problems.add("unsupported schemaVersion");
        }

        String status = manifest.path("status").asText("");
        String category = manifest.path("category").asText("");

        if ("PASSED".equals(status)) {
            for (String required : REQUIRED_FILES) {
                if (!Files.isRegularFile(runDir.resolve(required))) {
                    problems.add("required file missing: " + required);
                }
            }
            if (manifest.get("endedAtUtc") == null || manifest.get("endedAtUtc").isNull()) {
                problems.add("PASSED run has no endedAtUtc");
            } else {
                try {
                    Instant start = Instant.parse(manifest.get("startedAtUtc").asText());
                    Instant end = Instant.parse(manifest.get("endedAtUtc").asText());
                    if (end.isBefore(start)) {
                        problems.add("endedAtUtc before startedAtUtc");
                    }
                } catch (RuntimeException e) {
                    problems.add("unparseable timestamps");
                }
            }
            if (manifest.path("exitCode").asInt(-1) != 0) {
                problems.add("PASSED run with non-zero exit code");
            }
            verifyResults(runDir, problems);
            verifyChecksums(runDir, problems);
            verifyManifestHashes(runDir, manifest, problems);
        }

        if (NATIVE_CATEGORIES.contains(category)) {
            String provider = manifest.path("platformProviderId").asText("")
                    .toLowerCase(Locale.ROOT);
            if (provider.isEmpty() || provider.contains("fake") || provider.contains("mock")
                    || provider.equals("unknown") || provider.contains("headless-fake")) {
                problems.add("category '" + category + "' requires a real platform provider, got: '"
                        + provider + "'");
            }
        }
        return problems;
    }

    private void verifyResults(Path runDir, List<String> problems) {
        Path resultsPath = runDir.resolve("results.json");
        if (!Files.isRegularFile(resultsPath)) {
            return; // already reported
        }
        try {
            JsonNode results = mapper.readTree(Files.readString(resultsPath));
            JsonNode cases = results.path("cases");
            if (!cases.isArray() || cases.isEmpty()) {
                problems.add("PASSED run has no recorded cases");
                return;
            }
            for (JsonNode c : cases) {
                if (!c.path("passed").asBoolean(false)) {
                    problems.add("PASSED run contains failing case: " + c.path("name").asText());
                }
            }
        } catch (IOException e) {
            problems.add("results.json unreadable");
        }
    }

    private void verifyChecksums(Path runDir, List<String> problems) {
        Path checksums = runDir.resolve("checksums.sha256");
        if (!Files.isRegularFile(checksums)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(checksums)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(" {2}", 2);
                if (parts.length != 2) {
                    problems.add("malformed checksum line: " + line);
                    continue;
                }
                Path file = runDir.resolve(parts[1].strip());
                if (!Files.isRegularFile(file)) {
                    problems.add("checksummed file missing: " + parts[1]);
                    continue;
                }
                String actual = EvidenceRun.sha256(file);
                if (!actual.equals(parts[0])) {
                    problems.add("checksum mismatch for " + parts[1]
                            + " (evidence was modified after the run)");
                }
            }
        } catch (IOException e) {
            problems.add("checksums.sha256 unreadable");
        }
    }

    private void verifyManifestHashes(Path runDir, JsonNode manifest, List<String> problems) {
        JsonNode files = manifest.path("files");
        if (!files.isObject() || files.isEmpty()) {
            problems.add("PASSED manifest lists no files");
            return;
        }
        files.fields().forEachRemaining(entry -> {
            Path file = runDir.resolve(entry.getKey());
            if (!Files.isRegularFile(file)) {
                problems.add("manifest-listed file missing: " + entry.getKey());
            } else if (!EvidenceRun.sha256(file).equals(entry.getValue().asText())) {
                problems.add("manifest hash mismatch for " + entry.getKey());
            }
        });
    }
}
