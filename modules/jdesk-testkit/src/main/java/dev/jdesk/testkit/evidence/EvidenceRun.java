package dev.jdesk.testkit.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Machine-generated evidence for one native/package run (spec section 18). The manifest
 * is written with status {@code INCOMPLETE} at start; {@code PASSED} appears only after
 * {@link #finish(int)} finds every recorded case passed and the exit code is zero.
 * Crashed or abandoned runs therefore stay {@code INCOMPLETE} on disk. Never edit
 * evidence by hand; the verifier recomputes all checksums.
 */
public final class EvidenceRun implements AutoCloseable {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Path dir;
    private final String runId;
    private final String category;
    private final String command;
    private final Instant startedAt;
    private final List<CaseResult> cases = new ArrayList<>();
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final StringBuilder appLog = new StringBuilder();
    private Long applicationPid;
    private Long webViewPid;
    private String providerId = "unknown";
    private String webViewVersion = "unknown";
    private boolean finished;

    /** One assertion/probe outcome. */
    public record CaseResult(String name, boolean passed, String detail) {
    }

    private EvidenceRun(Path dir, String runId, String category, String command) {
        this.dir = dir;
        this.runId = runId;
        this.category = category;
        this.command = command;
        this.startedAt = Instant.now();
    }

    /**
     * @param baseDir usually {@code build/evidence}
     * @param category exact test category: {@code unit}, {@code native}, {@code package},
     *        {@code integration}, {@code release}
     * @param command the exact command being executed
     */
    public static EvidenceRun start(Path baseDir, String category, String command) {
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String runId = Instant.now().getEpochSecond() + "-" + HexFormat.of().formatHex(random);
        Path dir = baseDir.resolve(runId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        EvidenceRun run = new EvidenceRun(dir, runId, category, command);
        run.collectDefaultEnvironment();
        run.writeManifest("INCOMPLETE", null, null);
        run.writeEnvironmentFile();
        return run;
    }

    public String runId() {
        return runId;
    }

    public Path directory() {
        return dir;
    }

    public void providerId(String providerId) {
        this.providerId = providerId;
    }

    public void webViewVersion(String version) {
        this.webViewVersion = version;
    }

    public void applicationPid(long pid) {
        this.applicationPid = pid;
    }

    public void webViewPid(long pid) {
        this.webViewPid = pid;
    }

    public void putEnvironment(String key, String value) {
        environment.put(key, value);
        writeEnvironmentFile();
    }

    public synchronized void addCase(String name, boolean passed, String detail) {
        ensureNotFinished();
        cases.add(new CaseResult(name, passed, detail));
        log((passed ? "PASS " : "FAIL ") + name + ": " + detail);
    }

    public synchronized void log(String line) {
        appLog.append(Instant.now()).append(' ').append(line).append('\n');
    }

    /** Attaches raw bytes (e.g. screenshot.png) into the evidence directory. */
    public void attach(String filename, byte[] bytes) {
        ensureNotFinished();
        requireSafeName(filename);
        try {
            Files.write(dir.resolve(filename), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeStdStreams(String stdout, String stderr) {
        ensureNotFinished();
        try {
            Files.writeString(dir.resolve("stdout.log"), stdout, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("stderr.log"), stderr, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Finalizes the run: results.json, app.log, junit.xml, checksums, and the manifest
     * with the derived overall status. Idempotent-hostile on purpose: calling twice
     * throws — a run must have exactly one outcome.
     */
    public synchronized void finish(int exitCode) {
        ensureNotFinished();
        finished = true;
        boolean allPassed = exitCode == 0
                && !cases.isEmpty()
                && cases.stream().allMatch(CaseResult::passed);
        String status = allPassed ? "PASSED" : "FAILED";
        try {
            Files.writeString(dir.resolve("app.log"), appLog.toString(), StandardCharsets.UTF_8);
            writeResults(status, exitCode);
            writeJunitXml();
            if (!Files.exists(dir.resolve("stdout.log"))) {
                Files.writeString(dir.resolve("stdout.log"), "", StandardCharsets.UTF_8);
            }
            if (!Files.exists(dir.resolve("stderr.log"))) {
                Files.writeString(dir.resolve("stderr.log"), "", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        writeManifest(status, exitCode, Instant.now());
        writeChecksums();
    }

    @Override
    public synchronized void close() {
        if (!finished) {
            // Abandoned run: leave INCOMPLETE manifest, still flush logs for forensics.
            try {
                Files.writeString(dir.resolve("app.log"), appLog.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Nothing safe left to do while abandoning.
            }
        }
    }

    private void ensureNotFinished() {
        if (finished) {
            throw new IllegalStateException("Evidence run already finished");
        }
    }

    private static void requireSafeName(String filename) {
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("Unsafe evidence filename: " + filename);
        }
    }

    private void collectDefaultEnvironment() {
        environment.put("os.name", System.getProperty("os.name", "unknown"));
        environment.put("os.version", System.getProperty("os.version", "unknown"));
        environment.put("os.arch", System.getProperty("os.arch", "unknown"));
        environment.put("java.vendor", System.getProperty("java.vendor", "unknown"));
        environment.put("java.version", System.getProperty("java.version", "unknown"));
        environment.put("git.commit", gitOutput("rev-parse", "HEAD"));
        environment.put("git.dirty",
                gitOutput("status", "--porcelain").isEmpty() ? "false" : "true");
    }

    private static String gitOutput(String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git"));
            cmd.addAll(List.of(args));
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "unknown";
            }
            return output;
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private void writeEnvironmentFile() {
        ObjectNode node = mapper.createObjectNode();
        environment.forEach(node::put);
        writeJson("environment.json", node);
    }

    private void writeResults(String status, int exitCode) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", status);
        node.put("exitCode", exitCode);
        ArrayNode caseArray = node.putArray("cases");
        for (CaseResult result : cases) {
            ObjectNode c = caseArray.addObject();
            c.put("name", result.name());
            c.put("passed", result.passed());
            c.put("detail", result.detail());
        }
        writeJson("results.json", node);
    }

    private void writeJunitXml() throws IOException {
        StringBuilder xml = new StringBuilder();
        long failures = cases.stream().filter(c -> !c.passed()).count();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<testsuite name=\"").append(escapeXml(category)).append("-").append(runId)
                .append("\" tests=\"").append(cases.size())
                .append("\" failures=\"").append(failures).append("\">\n");
        for (CaseResult result : cases) {
            xml.append("  <testcase name=\"").append(escapeXml(result.name())).append("\"");
            if (result.passed()) {
                xml.append("/>\n");
            } else {
                xml.append("><failure message=\"").append(escapeXml(result.detail()))
                        .append("\"/></testcase>\n");
            }
        }
        xml.append("</testsuite>\n");
        Files.writeString(dir.resolve("junit.xml"), xml.toString(), StandardCharsets.UTF_8);
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void writeManifest(String status, Integer exitCode, Instant endedAt) {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", SCHEMA_VERSION);
        node.put("runId", runId);
        node.put("startedAtUtc", startedAt.toString());
        node.put("endedAtUtc", endedAt == null ? null : endedAt.toString());
        node.put("gitCommit", environment.getOrDefault("git.commit", "unknown"));
        node.put("gitDirty", environment.getOrDefault("git.dirty", "unknown"));
        node.put("osName", environment.get("os.name"));
        node.put("osVersion", environment.get("os.version"));
        node.put("architecture", environment.get("os.arch"));
        node.put("jdkVendor", environment.get("java.vendor"));
        node.put("jdkVersion", environment.get("java.version"));
        node.put("frameworkVersion", frameworkVersion());
        node.put("platformProviderId", providerId);
        node.put("webViewVersion", webViewVersion);
        node.put("category", category);
        node.put("command", command);
        if (applicationPid != null) {
            node.put("applicationPid", applicationPid);
        }
        if (webViewPid != null) {
            node.put("webViewPid", webViewPid);
        }
        if (exitCode != null) {
            node.put("exitCode", exitCode);
        }
        node.put("status", status);
        if (endedAt != null) {
            ObjectNode files = node.putObject("files");
            for (Map.Entry<String, String> entry : hashFiles().entrySet()) {
                files.put(entry.getKey(), entry.getValue());
            }
        }
        writeJson("manifest.json", node);
    }

    private static String frameworkVersion() {
        String version = EvidenceRun.class.getModule().getDescriptor() == null
                ? null
                : EvidenceRun.class.getModule().getDescriptor().rawVersion().orElse(null);
        if (version != null) {
            return version;
        }
        return System.getProperty("jdesk.version", "unknown");
    }

    private Map<String, String> hashFiles() {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("checksums.sha256") || name.equals("manifest.json")) {
                    continue;
                }
                hashes.put(name, sha256(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hashes;
    }

    private void writeChecksums() {
        StringBuilder out = new StringBuilder();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("checksums.sha256")) {
                    continue;
                }
                out.append(sha256(file)).append("  ").append(name).append('\n');
            }
            Files.writeString(dir.resolve("checksums.sha256"), out.toString(),
                    StandardCharsets.UTF_8);
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

    private void writeJson(String filename, ObjectNode node) {
        try {
            Files.writeString(dir.resolve(filename),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
