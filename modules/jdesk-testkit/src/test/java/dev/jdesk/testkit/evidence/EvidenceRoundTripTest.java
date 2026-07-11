package dev.jdesk.testkit.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceRoundTripTest {

    @TempDir
    Path base;

    private final ObjectMapper mapper = new ObjectMapper();

    private EvidenceRun startNativeRun() {
        EvidenceRun run = EvidenceRun.start(base, "native", "gradlew :test-apps:native-smoke:run");
        run.providerId("macos-wkwebview");
        run.webViewVersion("WebKit 620.1");
        return run;
    }

    @Test
    void passedRunProducesCompleteVerifiableEvidence() throws IOException {
        EvidenceRun run = startNativeRun();
        run.applicationPid(1234);
        run.addCase("handshake", true, "protocol v1 acknowledged");
        run.addCase("echo", true, "typed round trip ok");
        run.attach("screenshot.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        run.writeStdStreams("out", "err");
        run.finish(0);

        JsonNode manifest = mapper.readTree(Files.readString(run.directory().resolve("manifest.json")));
        assertThat(manifest.get("status").asText()).isEqualTo("PASSED");
        assertThat(manifest.get("platformProviderId").asText()).isEqualTo("macos-wkwebview");
        assertThat(manifest.get("category").asText()).isEqualTo("native");
        assertThat(manifest.get("runId").asText()).isEqualTo(run.runId());
        assertThat(manifest.get("files").size()).isGreaterThanOrEqualTo(6);

        List<String> problems = new EvidenceVerifier().verify(run.directory());
        assertThat(problems).isEmpty();
    }

    @Test
    void failingCaseMakesRunFailed() throws IOException {
        EvidenceRun run = startNativeRun();
        run.addCase("handshake", true, "ok");
        run.addCase("echo", false, "value mismatch");
        run.writeStdStreams("", "");
        run.finish(0);
        JsonNode manifest = mapper.readTree(Files.readString(run.directory().resolve("manifest.json")));
        assertThat(manifest.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void nonZeroExitCodeMakesRunFailed() throws IOException {
        EvidenceRun run = startNativeRun();
        run.addCase("handshake", true, "ok");
        run.finish(3);
        JsonNode manifest = mapper.readTree(Files.readString(run.directory().resolve("manifest.json")));
        assertThat(manifest.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void zeroCasesNeverPasses() throws IOException {
        EvidenceRun run = startNativeRun();
        run.finish(0);
        JsonNode manifest = mapper.readTree(Files.readString(run.directory().resolve("manifest.json")));
        assertThat(manifest.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void abandonedRunStaysIncomplete() throws IOException {
        EvidenceRun run = startNativeRun();
        run.addCase("handshake", true, "ok");
        run.close(); // never finished
        JsonNode manifest = mapper.readTree(Files.readString(run.directory().resolve("manifest.json")));
        assertThat(manifest.get("status").asText()).isEqualTo("INCOMPLETE");
    }

    @Test
    void finishTwiceThrows() {
        EvidenceRun run = startNativeRun();
        run.addCase("x", true, "ok");
        run.finish(0);
        assertThatIllegalStateException().isThrownBy(() -> run.finish(0));
    }

    @Test
    void verifierRejectsTamperedEvidence() throws IOException {
        EvidenceRun run = startNativeRun();
        run.addCase("handshake", true, "ok");
        run.writeStdStreams("", "");
        run.finish(0);
        // Tamper after the fact — exactly what rule 18 forbids.
        Files.writeString(run.directory().resolve("results.json"),
                Files.readString(run.directory().resolve("results.json")).replace("ok", "OK!"),
                StandardCharsets.UTF_8);
        List<String> problems = new EvidenceVerifier().verify(run.directory());
        assertThat(problems).anyMatch(p -> p.contains("checksum mismatch")
                || p.contains("hash mismatch"));
    }

    @Test
    void verifierRejectsFakeProvidersForNativeCategory() throws IOException {
        EvidenceRun run = EvidenceRun.start(base, "native", "cmd");
        run.providerId("headless-fake");
        run.addCase("handshake", true, "ok");
        run.writeStdStreams("", "");
        run.finish(0);
        List<String> problems = new EvidenceVerifier().verify(run.directory());
        assertThat(problems).anyMatch(p -> p.contains("real platform provider"));
    }

    @Test
    void unitCategoryMayUseFakes() throws IOException {
        EvidenceRun run = EvidenceRun.start(base, "unit", "gradlew test");
        run.providerId("unit-test-fake");
        run.addCase("state-machine", true, "ok");
        run.writeStdStreams("", "");
        run.finish(0);
        assertThat(new EvidenceVerifier().verify(run.directory())).isEmpty();
    }

    @Test
    void attachRejectsPathTraversalNames() {
        EvidenceRun run = startNativeRun();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> run.attach("../escape.png", new byte[] {1}));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> run.attach("a/b.png", new byte[] {1}));
    }

    @Test
    void junitXmlReflectsCases() throws IOException {
        EvidenceRun run = startNativeRun();
        run.addCase("good", true, "fine");
        run.addCase("bad", false, "broke <here> & \"there\"");
        run.finish(1);
        String xml = Files.readString(run.directory().resolve("junit.xml"));
        assertThat(xml).contains("tests=\"2\"").contains("failures=\"1\"")
                .contains("broke &lt;here&gt; &amp; &quot;there&quot;");
    }

    @Test
    void missingManifestIsReported() {
        assertThat(new EvidenceVerifier().verify(base.resolve("nope")))
                .containsExactly("manifest.json missing");
    }
}
