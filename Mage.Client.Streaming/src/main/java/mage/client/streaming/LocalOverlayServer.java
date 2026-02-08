package mage.client.streaming;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight local HTTP server used by OBS/Twitch overlay pages.
 * Serves static overlay assets and live game state JSON.
 */
public final class LocalOverlayServer {

    private static final Logger LOGGER = Logger.getLogger(LocalOverlayServer.class);
    private static final LocalOverlayServer INSTANCE = new LocalOverlayServer();

    private static final int DEFAULT_PORT = 17888;
    private static final int DEFAULT_PORT_SEARCH_ATTEMPTS = 100;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final String WAITING_STATE_JSON = "{\"status\":\"waiting\",\"message\":\"Waiting for game state\"}";
    private static final String FALLBACK_MOCK_STATE_JSON = "{\"status\":\"mock\"}";

    private final boolean enabled;
    private final String host;
    private final int requestedPort;
    private final int portSearchAttempts;
    private final AtomicReference<String> stateJson = new AtomicReference<>(WAITING_STATE_JSON);

    private HttpServer server;
    private ExecutorService executor;
    private volatile int boundPort;
    private volatile boolean running;

    private LocalOverlayServer() {
        this.enabled = parseBoolean(System.getProperty("xmage.streaming.overlay.enabled"), true);
        this.host = parseHost(System.getProperty("xmage.streaming.overlay.host"));
        this.requestedPort = parseInt(System.getProperty("xmage.streaming.overlay.port"), DEFAULT_PORT);
        this.portSearchAttempts = parseInt(
                System.getProperty("xmage.streaming.overlay.portSearchAttempts"),
                DEFAULT_PORT_SEARCH_ATTEMPTS
        );
        this.boundPort = this.requestedPort;

        if (enabled) {
            start();
        } else {
            LOGGER.info("Overlay server disabled by xmage.streaming.overlay.enabled=false");
        }
    }

    public static LocalOverlayServer getInstance() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return running;
    }

    public String getBaseUrl() {
        return "http://" + host + ":" + boundPort;
    }

    public void updateState(String json) {
        if (!running || json == null || json.isEmpty()) {
            return;
        }
        stateJson.set(json);
    }

    private synchronized void start() {
        if (running) {
            return;
        }

        HttpServer createdServer = null;
        IOException lastError = null;
        int maxAttempts = Math.max(1, portSearchAttempts);

        for (int offset = 0; offset < maxAttempts; offset++) {
            int candidatePort = requestedPort + offset;
            try {
                createdServer = HttpServer.create(new InetSocketAddress(host, candidatePort), 0);
                boundPort = candidatePort;
                if (offset > 0) {
                    LOGGER.warn("Overlay port " + requestedPort + " unavailable, using fallback port " + candidatePort);
                }
                break;
            } catch (IOException e) {
                lastError = e;
            }
        }

        if (createdServer == null) {
            LOGGER.error(
                    "Failed to start overlay server on " + host + ":" + requestedPort
                            + " (searched " + maxAttempts + " ports)",
                    lastError
            );
            stop();
            return;
        }

        server = createdServer;
        server.createContext("/api/state", this::handleState);
        server.createContext("/api/mock-state", this::handleMockState);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/", this::handleStatic);

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "xmage-overlay-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
        running = true;

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "xmage-overlay-http-shutdown"));
        LOGGER.info("Overlay server listening at " + getBaseUrl() + "/");
        LOGGER.info("OBS Browser Source URL: " + getBaseUrl() + "/");
        LOGGER.info("Local mock test URL: " + getBaseUrl() + "/?mock=1");
    }

    private synchronized void stop() {
        running = false;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleState(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) {
            return;
        }
        respondJson(exchange, 200, stateJson.get());
    }

    private void handleMockState(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) {
            return;
        }
        byte[] payload = readResourceBytes("/overlay/mock-state.json");
        String json = payload == null ? FALLBACK_MOCK_STATE_JSON : new String(payload, StandardCharsets.UTF_8);
        respondJson(exchange, 200, json);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) {
            return;
        }
        respondJson(exchange, 200, "{\"ok\":true}");
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (handleCorsPreflight(exchange)) {
            return;
        }

        String path = exchange.getRequestURI().getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/index.html";
        }

        if (path.contains("..")) {
            respondText(exchange, 400, "Bad request");
            return;
        }

        if (path.startsWith("/api/")) {
            respondText(exchange, 404, "Not found");
            return;
        }

        byte[] bytes = readResourceBytes("/overlay" + path);
        if (bytes == null) {
            respondText(exchange, 404, "Not found");
            return;
        }

        Headers headers = exchange.getResponseHeaders();
        addCorsHeaders(headers);
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Type", detectContentType(path));
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] readResourceBytes(String resourcePath) throws IOException {
        try (InputStream in = LocalOverlayServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        addCorsHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        addCorsHeaders(headers);
        headers.set("Content-Type", "text/plain; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean handleCorsPreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }
        Headers headers = exchange.getResponseHeaders();
        addCorsHeaders(headers);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String detectContentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.equals("1") || normalized.equals("true")
                || normalized.equals("yes") || normalized.equals("on")) {
            return true;
        }
        if (normalized.equals("0") || normalized.equals("false")
                || normalized.equals("no") || normalized.equals("off")) {
            return false;
        }
        return defaultValue;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String parseHost(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_HOST;
        }
        return value.trim();
    }
}
