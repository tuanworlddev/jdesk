package dev.jdesk.automation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jdesk.api.WindowId;
import dev.jdesk.runtime.automation.AutomationHost;
import dev.jdesk.runtime.automation.AutomationSession;
import dev.jdesk.runtime.json.JacksonJsonCodec;
import dev.jdesk.runtime.json.JsonCodec;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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
final class AutomationServer implements AutomationSession {
    private static final Logger LOG = System.getLogger(AutomationServer.class.getName());
    private static final int CONSOLE_BUFFER = 500;
    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;

    /** POST /evaluate request body (public for JSON binding). */
    public record EvaluateRequest(String window, String script) {
    }

    /** One captured page-console line (public for JSON binding). */
    public record ConsoleLine(String window, String level, String message) {
    }

    /**
     * POST /input request. Synthesizes DOM interaction on a target element (CSS
     * {@code selector}). Actions: {@code click}, {@code focus}, {@code type} (sets value
     * and fires input/change), {@code key} (keydown/keyup with {@code key}), {@code hover}
     * (mouseover/mousemove). These are synthetic DOM events (isTrusted=false) — enough
     * for automation, but real OS-level hover CSS and IME composition are NOT reproduced.
     */
    public record InputRequest(String window, String action, String selector, String text,
            String key) {
    }

    private final AutomationHost host;
    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] tokenBytes;
    private final Path descriptorFile;
    private final JsonCodec codec = new JacksonJsonCodec();
    private final ArrayDeque<ConsoleLine> consoleLines = new ArrayDeque<>();

    AutomationServer(AutomationHost host, String applicationId) throws IOException {
        this.host = host;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = HexFormat.of().formatHex(random);
        this.tokenBytes = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);

        this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/windows", exchange -> guarded(exchange, this::handleWindows));
        server.createContext("/evaluate", exchange -> guarded(exchange, this::handleEvaluate));
        server.createContext("/snapshot", exchange -> guarded(exchange, this::handleSnapshot));
        server.createContext("/console", exchange -> guarded(exchange, this::handleConsole));
        server.createContext("/input", exchange -> guarded(exchange, this::handleInput));
        String configured = System.getProperty("jdesk.automation.dir");
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".jdesk", "automation")
                : Path.of(configured);
        Files.createDirectories(directory);
        restrictDirectoryToOwner(directory);
        this.descriptorFile = directory.resolve(applicationId + ".json");
        // Owner-only BEFORE the token hits the disk: create the file with restricted
        // permissions (or restrict the empty file where atomic create-with-attrs is
        // unsupported), then write the content.
        try {
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
            server.start();
        } catch (IOException | RuntimeException e) {
            server.stop(0);
            executor.shutdownNow();
            try {
                Files.deleteIfExists(descriptorFile);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
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

    private static void restrictDirectoryToOwner(Path directory) throws IOException {
        var view = Files.getFileAttributeView(directory,
                java.nio.file.attribute.PosixFileAttributeView.class);
        if (view != null) {
            view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString(
                    "rwx------"));
        }
    }

    /** Records one captured page-console line (called from the runtime's console sink). */
    @Override
    public void recordConsole(WindowId windowId, String level, String message) {
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
            } catch (RequestTooLargeException e) {
                // The rejected body is deliberately not drained: draining attacker-sized
                // input would defeat the resource limit. Tell the client this connection
                // cannot be reused before closing the exchange and its request stream.
                exchange.getResponseHeaders().set("Connection", "close");
                sendJson(exchange, 413, Map.of("error", "request body too large"));
            } catch (Exception e) {
                LOG.log(Level.DEBUG, "Automation request failed", e);
                sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
    }

    private void handleWindows(HttpExchange exchange) throws IOException {
        List<String> ids = host.openWindowIds().stream().map(WindowId::value).sorted().toList();
        sendJson(exchange, 200, Map.of("windows", ids));
    }

    private void handleEvaluate(HttpExchange exchange) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        String body = readBody(exchange);
        EvaluateRequest request = codec.decode(body, EvaluateRequest.class);
        if (request.script() == null || request.script().isBlank()) {
            sendJson(exchange, 400, Map.of("error", "script is required"));
            return;
        }
        String window = request.window() == null ? "main" : request.window();
        // Wrap the caller's expression so the page returns JSON.stringify of its value:
        // `result` is the parsed JSON (objects/arrays/numbers/booleans/null), while
        // `value` keeps the raw string form for back-compat. A non-serializable or
        // throwing expression yields result:null with the raw string in `value`.
        String wrapped = "(function(){try{return JSON.stringify((function(){return ("
                + request.script() + ");})());}catch(e){return undefined;}})()";
        String raw = host.evaluate(new WindowId(window), wrapped)
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("value", String.valueOf(raw));
        response.put("result", parseJsonOrNull(raw));
        sendJson(exchange, 200, response);
    }

    /** Parses an engine-returned JSON string into a tree, or null when not JSON. */
    private Object parseJsonOrNull(String raw) {
        if (raw == null || raw.isBlank() || "undefined".equals(raw)) {
            return null;
        }
        try {
            return codec.decode(raw, Object.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void handleInput(HttpExchange exchange) throws Exception {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        String body = readBody(exchange);
        InputRequest request = codec.decode(body, InputRequest.class);
        String window = request.window() == null ? "main" : request.window();
        if (request.selector() == null || request.selector().isBlank()) {
            sendJson(exchange, 400, Map.of("error", "selector is required"));
            return;
        }
        // Wrap in JSON.stringify so the boolean serializes to "true"/"false" on every
        // engine (a bare JS boolean stringifies to "1"/"0" on WKWebView).
        String raw = host.evaluate(new WindowId(window),
                        "JSON.stringify(" + inputScript(request) + ")")
                .toCompletableFuture().get(15, TimeUnit.SECONDS);
        boolean ok = "true".equals(raw);
        sendJson(exchange, ok ? 200 : 422,
                Map.of("ok", ok, "detail", ok ? "dispatched" : "element not found or action failed"));
    }

    /** Builds a self-contained page script that performs the requested DOM interaction. */
    private static String inputScript(InputRequest request) {
        String selector = jsString(request.selector());
        String action = request.action() == null ? "click" : request.action();
        String text = jsString(request.text() == null ? "" : request.text());
        String key = jsString(request.key() == null ? "" : request.key());
        return "(function(){var el=document.querySelector(" + selector + ");"
                + "if(!el){return false;}"
                + "var base={bubbles:true,cancelable:true};"
                + "function mouse(t){el.dispatchEvent(new MouseEvent(t,Object.assign({},base)));}"
                + "function keyev(t,k){el.dispatchEvent(new KeyboardEvent(t,"
                + "Object.assign({key:k},base)));}"
                + "function plain(t){el.dispatchEvent(new Event(t,Object.assign({},base)));}"
                + "var a=" + jsString(action) + ";"
                + "if(a==='click'){el.focus&&el.focus();mouse('mousedown');mouse('mouseup');"
                + "el.click?el.click():mouse('click');}"
                + "else if(a==='focus'){el.focus&&el.focus();}"
                + "else if(a==='hover'){mouse('mouseover');mouse('mousemove');}"
                + "else if(a==='type'){el.focus&&el.focus();"
                + "if('value' in el){el.value=" + text + ";}else{el.textContent=" + text + ";}"
                + "plain('input');plain('change');}"
                + "else if(a==='key'){var k=" + key + ";keyev('keydown',k);keyev('keyup',k);}"
                + "else{return false;}return true;})()";
    }

    private static String jsString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private void handleSnapshot(HttpExchange exchange) throws Exception {
        String window = queryParams(exchange).getOrDefault("window", "main");
        byte[] snapshot = host.snapshotPng(new WindowId(window))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, snapshot.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(snapshot);
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

    private static String readBody(HttpExchange exchange)
            throws IOException, RequestTooLargeException {
        long declared = exchange.getRequestHeaders().getFirst("Content-Length") == null
                ? -1 : Long.parseLong(exchange.getRequestHeaders().getFirst("Content-Length"));
        if (declared > MAX_REQUEST_BODY_BYTES) {
            throw new RequestTooLargeException();
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BODY_BYTES) {
            throw new RequestTooLargeException();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class RequestTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
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
        executor.shutdownNow();
        try {
            Files.deleteIfExists(descriptorFile);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Automation descriptor not removed", e);
        }
    }
}
