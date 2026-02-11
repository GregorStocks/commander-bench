package mage.client.streaming;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight local HTTP server that exposes live game state JSON.
 * The actual overlay UI lives in the website (games/live page).
 */
public final class LocalOverlayServer {

    private static final Logger LOGGER = Logger.getLogger(LocalOverlayServer.class);
    private static final LocalOverlayServer INSTANCE = new LocalOverlayServer();

    private static final int DEFAULT_PORT = 17888;
    private static final int DEFAULT_PORT_SEARCH_ATTEMPTS = 100;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final String WAITING_STATE_JSON = "{\"status\":\"waiting\",\"message\":\"Waiting for game state\"}";
    private static final String FALLBACK_MOCK_STATE_JSON = "{\"status\":\"mock\"}";

    private static final String LIVE_VIEWER_HTML = "<!DOCTYPE html>\n"
            + "<html lang=\"en\"><head><meta charset=\"utf-8\"/>\n"
            + "<meta name=\"viewport\" content=\"width=device-width\"/>\n"
            + "<title>Live Game | Mage-Bench</title>\n"
            + "<link rel=\"stylesheet\" href=\"/game-renderer.css\"/>\n"
            + "<style>\n"
            + "*, *::before, *::after { box-sizing: border-box; margin: 0; }\n"
            + "body { font-family: system-ui, -apple-system, sans-serif; color: #e0e0e0;\n"
            + "  background: #1a1a2e; min-height: 100vh; display: flex; flex-direction: column; }\n"
            + "nav { display: flex; align-items: center; gap: 2rem;\n"
            + "  padding: 1rem 2rem; background: #16213e; border-bottom: 1px solid #0f3460; }\n"
            + ".site-title { font-size: 1.25rem; font-weight: 700; color: #e94560; text-decoration: none; }\n"
            + "nav ul { display: flex; list-style: none; gap: 1.5rem; padding: 0; }\n"
            + "nav a { color: #a0a0b8; text-decoration: none; } nav a:hover { color: #e94560; }\n"
            + "main { flex: 1; padding: 2rem; width: 100%; }\n"
            + "footer { text-align: center; padding: 2rem; color: #666; border-top: 1px solid #0f3460; }\n"
            + "footer a { color: #a0a0b8; text-decoration: none; } footer a:hover { color: #e94560; }\n"
            + "#connection-status { text-align: center; padding: 4rem 0; color: #a0a0b8; }\n"
            + "#connection-status.error { color: #e94560; }\n"
            + "#game-header { display: flex; justify-content: space-between; align-items: baseline;\n"
            + "  gap: 1rem; padding: 0.75rem 1rem; background: #16213e;\n"
            + "  border: 1px solid #0f3460; border-radius: 8px; margin-bottom: 0.75rem; }\n"
            + "#game-title { font-size: 1rem; font-weight: 600; color: #e94560; }\n"
            + "#turn-info { font-size: 0.85rem; color: #a0a0b8; text-align: right; }\n"
            + ".hidden { display: none !important; }\n"
            + "</style></head><body>\n"
            + "<nav><a class=\"site-title\" href=\"/\">Mage-Bench</a><ul>\n"
            + "  <li><a href=\"/leaderboard\">Leaderboard</a></li>\n"
            + "  <li><a href=\"/about\">About</a></li>\n"
            + "  <li><a href=\"/games\">Games</a></li>\n"
            + "</ul></nav>\n"
            + "<main><div id=\"live-visualizer\">\n"
            + "  <div id=\"connection-status\">Connecting to game server...</div>\n"
            + "  <div id=\"game-ui\" class=\"hidden\">\n"
            + "    <div id=\"game-header\" class=\"game-header-bar\">\n"
            + "      <div id=\"game-title\">Live Game</div><div id=\"turn-info\"></div></div>\n"
            + "    <div id=\"players-grid\"></div>\n"
            + "    <div id=\"stack-section\" class=\"hidden\">\n"
            + "      <div class=\"section-title\">Stack</div>\n"
            + "      <div id=\"stack-cards\" class=\"cards-row\"></div></div>\n"
            + "    <div id=\"position-layer\" class=\"position-layer hidden\"></div>\n"
            + "    <div id=\"card-preview\" class=\"hidden\">\n"
            + "      <img id=\"preview-image\" alt=\"\"/>\n"
            + "      <div class=\"card-meta\">\n"
            + "        <div id=\"preview-name\" class=\"card-name\"></div>\n"
            + "        <div id=\"preview-type\" class=\"card-type\"></div>\n"
            + "        <div id=\"preview-stats\" class=\"card-stats\"></div>\n"
            + "        <pre id=\"preview-rules\" class=\"card-rules\"></pre>\n"
            + "      </div></div>\n"
            + "  </div>\n"
            + "</div></main>\n"
            + "<footer><p><a href=\"https://github.com/GregorStocks/mage-bench\">GitHub</a></p></footer>\n"
            + "<script src=\"/game-renderer.js\"></script>\n"
            + "<script>\n"
            + "(function () {\n"
            + "  var R = window.GameRenderer;\n"
            + "  var params = new URLSearchParams(window.location.search);\n"
            + "  var apiBase = window.location.origin;\n"
            + "  var pollMs = Math.max(250, Number(params.get('pollMs') || 700));\n"
            + "  var usePositioned = params.get('positions') === '1';\n"
            + "  var useMock = params.get('mock') === '1';\n"
            + "  var obsMode = params.get('obs') === '1';\n"
            + "  if (obsMode) {\n"
            + "    var nav = document.querySelector('nav'); if (nav) nav.style.display = 'none';\n"
            + "    var footer = document.querySelector('footer'); if (footer) footer.style.display = 'none';\n"
            + "    document.body.style.background = 'transparent';\n"
            + "    var mainEl = document.querySelector('main');\n"
            + "    if (mainEl) { mainEl.style.padding = '0'; mainEl.style.margin = '0'; }\n"
            + "  }\n"
            + "  var statusEl = document.getElementById('connection-status');\n"
            + "  var gameUI = document.getElementById('game-ui');\n"
            + "  var turnInfoEl = document.getElementById('turn-info');\n"
            + "  var playersGrid = document.getElementById('players-grid');\n"
            + "  var stackSection = document.getElementById('stack-section');\n"
            + "  var stackCards = document.getElementById('stack-cards');\n"
            + "  var positionLayer = document.getElementById('position-layer');\n"
            + "  var visualizer = document.getElementById('live-visualizer');\n"
            + "  var previewEls = {\n"
            + "    container: document.getElementById('card-preview'),\n"
            + "    image: document.getElementById('preview-image'),\n"
            + "    name: document.getElementById('preview-name'),\n"
            + "    type: document.getElementById('preview-type'),\n"
            + "    stats: document.getElementById('preview-stats'),\n"
            + "    rules: document.getElementById('preview-rules')\n"
            + "  };\n"
            + "  var requestInFlight = false, playerColorMap = {}, playersInitialized = false, prevState = null;\n"
            + "  function initPlayerColors(players) {\n"
            + "    if (playersInitialized) return;\n"
            + "    (players || []).forEach(function (p, i) { playerColorMap[p.name] = i % 4; });\n"
            + "    playersInitialized = true;\n"
            + "  }\n"
            + "  async function tick() {\n"
            + "    if (requestInFlight) return; requestInFlight = true;\n"
            + "    try {\n"
            + "      var url = useMock ? apiBase + '/api/mock-state' : apiBase + '/api/state';\n"
            + "      var resp = await fetch(url, { cache: 'no-store' });\n"
            + "      if (!resp.ok) throw new Error('HTTP ' + resp.status);\n"
            + "      var raw = await resp.json();\n"
            + "      if (raw && raw.status === 'waiting') {\n"
            + "        statusEl.textContent = 'Waiting for game to start...';\n"
            + "        statusEl.classList.remove('error'); return;\n"
            + "      }\n"
            + "      var state = R.normalizeLiveState(raw);\n"
            + "      if (statusEl && !statusEl.classList.contains('hidden')) {\n"
            + "        statusEl.classList.add('hidden'); gameUI.classList.remove('hidden');\n"
            + "      }\n"
            + "      initPlayerColors(state.players);\n"
            + "      var diffs = prevState ? R.computeDiff(prevState, state) : null;\n"
            + "      prevState = state;\n"
            + "      R.renderStatusLine(turnInfoEl, state);\n"
            + "      if (usePositioned) {\n"
            + "        var rendered = R.renderPositionLayer(positionLayer, state, visualizer, previewEls);\n"
            + "        if (rendered) { playersGrid.classList.add('hidden'); }\n"
            + "        else {\n"
            + "          playersGrid.classList.remove('hidden'); positionLayer.classList.add('hidden');\n"
            + "          if (visualizer) visualizer.classList.remove('positioned-mode');\n"
            + "          R.renderPlayers(playersGrid, state.players, { cardImages: {},\n"
            + "            playerColorMap: playerColorMap, diffs: diffs, previewEls: previewEls,\n"
            + "            showTimer: true, priorityPlayerName: state.priority_player });\n"
            + "        }\n"
            + "      } else {\n"
            + "        R.renderPlayers(playersGrid, state.players, { cardImages: {},\n"
            + "          playerColorMap: playerColorMap, diffs: diffs, previewEls: previewEls,\n"
            + "          showTimer: true, priorityPlayerName: state.priority_player });\n"
            + "      }\n"
            + "      R.renderStack(stackSection, stackCards, state.stack, {}, previewEls);\n"
            + "    } catch (err) {\n"
            + "      if (gameUI.classList.contains('hidden')) {\n"
            + "        statusEl.textContent = 'Cannot reach game server \\u2014 retrying...';\n"
            + "        statusEl.classList.add('error');\n"
            + "      }\n"
            + "    } finally { requestInFlight = false; }\n"
            + "  }\n"
            + "  window.addEventListener('blur', function () { R.hidePreview(previewEls); });\n"
            + "  document.addEventListener('keydown', function (e) {\n"
            + "    if (e.key === 'Escape') R.hidePreview(previewEls);\n"
            + "  });\n"
            + "  if (usePositioned) window.addEventListener('resize', function () { tick(); });\n"
            + "  tick(); window.setInterval(tick, pollMs);\n"
            + "})();\n"
            + "</script></body></html>";

    /** Files allowed to be served from the webroot. */
    private static final Set<String> ALLOWED_STATIC_FILES = Set.of(
            "game-renderer.js", "game-renderer.css"
    );

    private final boolean enabled;
    private final String host;
    private final int requestedPort;
    private final int portSearchAttempts;
    private final Path webroot;
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
        String webrootProp = System.getProperty("xmage.streaming.overlay.webroot");
        this.webroot = webrootProp != null ? Paths.get(webrootProp) : null;
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
        LOGGER.info("Overlay API server listening at " + getBaseUrl() + "/api/state");
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

        // Serve mock-state.json if requested directly
        if ("/mock-state.json".equals(path)) {
            handleMockState(exchange);
            return;
        }

        if (path != null && path.startsWith("/api/")) {
            respondText(exchange, 404, "Not found");
            return;
        }

        // Serve the live viewer page
        if ("/live".equals(path)) {
            respondBytes(exchange, 200, "text/html; charset=utf-8", LIVE_VIEWER_HTML.getBytes(StandardCharsets.UTF_8));
            return;
        }

        // Serve allowed static files from webroot (game-renderer.js/css)
        if (webroot != null && path != null && path.startsWith("/")) {
            String filename = path.substring(1);
            if (ALLOWED_STATIC_FILES.contains(filename)) {
                Path file = webroot.resolve(filename);
                if (Files.isRegularFile(file)) {
                    byte[] content = Files.readAllBytes(file);
                    respondBytes(exchange, 200, detectContentType(path), content);
                    return;
                }
            }
        }

        // Default: redirect to /live
        Headers headers = exchange.getResponseHeaders();
        headers.set("Location", "/live");
        exchange.sendResponseHeaders(302, -1);
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

    private static void respondBytes(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        addCorsHeaders(headers);
        headers.set("Content-Type", contentType);
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
