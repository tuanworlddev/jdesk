package dev.jdesk.runtime.boot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import dev.jdesk.runtime.json.JsonCodec;
import dev.jdesk.webview.spi.WebViewSnapshot;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Explicitly opt-in automation endpoint for E2E tests, CI, and agents — JDesk's
 * equivalent of a remote-debugging port. Started only when the application is launched
 * with {@code -Djdesk.automation=true}; never in normal runs.
 *
 * <p>Security posture: binds 127.0.0.1 on an ephemeral port; every request must carry
 * {@code Authorization: Bearer <token>} with a per-run random token. The endpoint
 * descriptor {@code {pid, port, token}} is written with owner-only permissions to
 * {@code ~/.jdesk/automation/<applicationId>.json} (directory overridable via
 * {@code jdesk.automation.dir}) where the driving process picks it up.
 *
 * <p>Endpoints: {@code GET /windows}, {@code POST /evaluate} {@code {window, script}},
 * {@code GET /snapshot?window=<id>} (PNG), {@code GET /console?window=<id>} (captured
 * page console lines).
 */
final class AutomationServer implements AutoCloseable {
    private static final Logger LOG = System.getLogger(AutomationServer.class.getName());
    private static final int CONSOLE_BUFFER = 500;

    /** POST /evaluate request body (public for JSON binding). */
    public record EvaluateRequest(String window, String script) {
    }

    /** One captured page-console line (public for JSON binding). */
    public record ConsoleLine(String window, String level, String message) {
    }

    private final JDeskRuntime runtime;
    private final HttpServer server;
    private final byte[] tokenBytes;
    private final Path descriptorFile;
    private final JsonCodec codec = new JacksonJsonCodec();
    private final ArrayDeque<ConsoleLine> consoleLines = new ArrayDeque<>();

    static Optional<AutomationServer> startIfEnabled(JDeskRuntime runtime, String applicationId) {
        if (!Boolean.getBoolean("jdesk.automation")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AutomationServer(runtime, applicationId));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Automation endpoint could not start", e);
            return Optional.empty();
        }
    }

    private AutomationServer(JDeskRuntime runtime, String applicationId) throws IOException {
        this.runtime = runtime;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = HexFormat.of().formatHex(random);
        this.tokenBytes = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);

        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/windows", exchange -> guarded(exchange, this::handleWindows));
        server.createContext("/evaluate", exchange -> guarded(exchange, this::handleEvaluate));
        server.createContext("/snapshot", exchange -> guarded(exchange, this::handleSnapshot));
        server.createContext("/console", exchange -> guarded(exchange, this::handleConsole));
        server.start();

        String configured = System.getProperty("jdesk.automation.dir");
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".jdesk", "automation")
                : Path.of(configured);
        Files.createDirectories(directory);
        this.descriptorFile = directory.resolve(applicationId + ".json");
        // Owner-only BEFORE the token hits the disk: create the file with restricted
        // permissions (or restrict the empty file where atomic create-with-attrs is
        // unsupported), then write the content.
        Files.deleteIfExists(descriptorFile);
        try {
            Files.createFile(descriptorFile,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(descriptorFile);
            restrictToOwner(descriptorFile);
        }
        Files.writeString(descriptorFile, codec.encode(Map.of(
                "pid", ProcessHandle.current().pid(),
                "port", server.getAddress().getPort(),
                "token", token)));
        // Deliberately does NOT print the token; the descriptor file carries it.
        System.out.println("JDESK-AUTOMATION port=" + server.getAddress().getPort()
                + " descriptor=" + descriptorFile.toAbsolutePath());
        LOG.log(Level.INFO, "Automation endpoint on 127.0.0.1:{0}",
                server.getAddress().getPort());
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException e) {
            LOG.log(Level.DEBUG, "POSIX permissions unavailable for {0}", file);
        }
    }

    /** Records one captured page-console line (called from the runtime's console sink). */
    void recordConsole(WindowId windowId, String level, String message) {
        ConsoleLine line = new ConsoleLine(windowId.value(), level, message);
        synchronized (consoleLines) {
            if (consoleLines.size() >= CONSOLE_BUFFER) {
                consoleLines.removeFirst();
            }
            consoleLines.addLast(line);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private void guarded(HttpExchange exchange, Handler handler) throws IOException {
        try (exchange) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] presented = authorization == null
                    ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(tokenBytes, presented)) {
                sendJson(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                LOG.log(Level.DEBUG, "Automation request failed", e);
                sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
    }

    private void handleWindows(HttpExchange exchange) throws IOException {
        List<String> ids = runtime.openWindowIds().stream().map(WindowId::value).sorted().toList();
        sendJson(exchange, 200, Map.of("windows", ids));
    }

    private void handleEvaluate(HttpExchange exchange) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        EvaluateRequest request = codec.decode(body, EvaluateRequest.class);
        if (request.script() == null || request.script().isBlank()) {
            sendJson(exchange, 400, Map.of("error", "script is required"));
            return;
        }
        String window = request.window() == null ? "main" : request.window();
        String value = runtime.evaluate(new WindowId(window), request.script())
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
        sendJson(exchange, 200, Map.of("value", String.valueOf(value)));
    }

    private void handleSnapshot(HttpExchange exchange) throws Exception {
        String window = queryParams(exchange).getOrDefault("window", "main");
        WebViewSnapshot snapshot = runtime.snapshot(new WindowId(window))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, snapshot.png().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(snapshot.png());
        }
    }

    private void handleConsole(HttpExchange exchange) throws IOException {
        String window = queryParams(exchange).get("window");
        List<ConsoleLine> lines = new ArrayList<>();
        synchronized (consoleLines) {
            for (ConsoleLine line : consoleLines) {
                if (window == null || window.equals(line.window())) {
                    lines.add(line);
                }
            }
        }
        sendJson(exchange, 200, Map.of("lines", lines));
    }

    private static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = codec.encode(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        try {
            Files.deleteIfExists(descriptorFile);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Automation descriptor not removed", e);
        }
    }
}
