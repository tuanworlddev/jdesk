package dev.jdesk.runtime.boot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Automation endpoint (-Djdesk.automation=true): token-gated loopback HTTP access to
 * windows/evaluate/console against a running runtime with the fake platform provider.
 */
@Timeout(30)
class AutomationServerTest {

    @TempDir
    Path automationDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void enableAutomation() {
        System.setProperty("jdesk.automation", "true");
        System.setProperty("jdesk.automation.dir", automationDir.toString());
    }

    @AfterEach
    void disableAutomation() {
        System.clearProperty("jdesk.automation");
        System.clearProperty("jdesk.automation.dir");
    }

    @Test
    void endpointRequiresTokenAndServesWindowsEvaluateConsole() throws Exception {
        try (JDeskRuntimeTest.RunningRuntime running = new JDeskRuntimeTest.RunningRuntime(
                List.of(JDeskRuntimeTest.RunningRuntime.window("main")))) {
            running.awaitReady();

            Path descriptorFile = automationDir.resolve("dev.jdesk.test.json");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(descriptorFile) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(descriptorFile).exists();
            JsonNode descriptor = mapper.readTree(Files.readString(descriptorFile));
            int port = descriptor.get("port").intValue();
            String token = descriptor.get("token").asText();
            String base = "http://127.0.0.1:" + port;

            // Without (or with a wrong) bearer token: rejected.
            HttpResponse<String> unauthorized = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(base + "/windows")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);

            // Windows listing.
            HttpResponse<String> windows = client.send(authorized(base + "/windows", token)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(windows.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(windows.body()).get("windows").get(0).asText())
                    .isEqualTo("main");

            // Evaluate routes through the runtime to the (fake) webview.
            HttpResponse<String> evaluated = client.send(authorized(base + "/evaluate", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"window\":\"main\",\"script\":\"1+1\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(evaluated.statusCode()).isEqualTo(200);
            // Both the raw string (value) and the parsed JSON tree (result) are present.
            assertThat(mapper.readTree(evaluated.body()).has("value")).isTrue();
            assertThat(mapper.readTree(evaluated.body()).has("result")).isTrue();

            // /input requires a selector and returns a structured ok/detail. The fake
            // webview yields an empty evaluate result, so the dispatch reports not-ok.
            HttpResponse<String> noSelector = client.send(authorized(base + "/input", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"window\":\"main\",\"action\":\"click\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(noSelector.statusCode()).isEqualTo(400);

            HttpResponse<String> input = client.send(authorized(base + "/input", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"window\":\"main\",\"action\":\"click\",\"selector\":\"#go\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(input.statusCode()).isEqualTo(422); // fake webview: not dispatched
            assertThat(mapper.readTree(input.body()).has("ok")).isTrue();

            // Console buffer starts empty and answers with a lines array.
            HttpResponse<String> console = client.send(
                    authorized(base + "/console?window=main", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(console.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(console.body()).get("lines").isArray()).isTrue();

            // Unknown window on evaluate maps to a 500 with an error body, not a hang.
            HttpResponse<String> missing = client.send(authorized(base + "/evaluate", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"window\":\"nope\",\"script\":\"1\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(500);

            running.runtime.requestStop();
            running.thread.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(Files.exists(descriptorFile))
                    .as("descriptor is removed on shutdown")
                    .isFalse();
        }
    }

    private static HttpRequest.Builder authorized(String uri, String token) {
        return HttpRequest.newBuilder().uri(URI.create(uri))
                .header("Authorization", "Bearer " + token);
    }
}
