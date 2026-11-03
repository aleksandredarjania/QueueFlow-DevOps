package queueflow;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class QueueServer implements AutoCloseable {
    private final QueueManager manager;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final boolean failHealth;

    public QueueServer(QueueManager manager, int port, boolean failHealth) throws IOException {
        this.manager = manager;
        this.failHealth = failHealth;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/health") && method.equals("GET")) {
                int status = failHealth ? 503 : 200;
                send(exchange, status, "application/json", failHealth
                        ? "{\"status\":\"unhealthy\"}" : "{\"status\":\"ok\",\"service\":\"QueueFlow\"}");
            } else if (path.equals("/api/stats") && method.equals("GET")) {
                send(exchange, 200, "application/json", manager.stats().toJson());
            } else if (path.equals("/api/tickets") && method.equals("GET")) {
                List<Ticket> tickets = manager.tickets();
                send(exchange, 200, "application/json", tickets.stream()
                        .map(Ticket::toJson).collect(Collectors.joining(",", "[", "]")));
            } else if (path.equals("/api/tickets") && method.equals("POST")) {
                Map<String, String> fields = readForm(exchange);
                send(exchange, 201, "application/json", manager.issue(
                        fields.get("customer"), fields.get("service")).toJson());
            } else if (path.equals("/api/next") && method.equals("POST")) {
                send(exchange, 200, "application/json", manager.callNext().toJson());
            } else if (path.equals("/api/finish") && method.equals("POST")) {
                send(exchange, 200, "application/json", manager.finishCurrent().toJson());
            } else if (method.equals("GET") && (path.equals("/") || path.equals("/index.html")
                    || path.equals("/styles.css") || path.equals("/app.js"))) {
                serveAsset(exchange, path.equals("/") ? "/index.html" : path);
            } else {
                send(exchange, 404, "application/json", "{\"error\":\"Not found\"}");
            }
        } catch (IllegalArgumentException error) {
            send(exchange, 400, "application/json", errorJson(error.getMessage()));
        } catch (IllegalStateException error) {
            send(exchange, 409, "application/json", errorJson(error.getMessage()));
        } catch (Exception error) {
            System.err.println("Request error: " + error.getClass().getSimpleName());
            send(exchange, 500, "application/json", "{\"error\":\"Internal server error\"}");
        } finally {
            exchange.close();
        }
    }

    private static String errorJson(String message) {
        return "{\"error\":" + Json.quote(message) + "}";
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) {
            throw new IllegalArgumentException("Expected a form-encoded request.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(8193);
        if (bytes.length > 8192) throw new IllegalArgumentException("Request too large.");
        Map<String, String> fields = new HashMap<>();
        for (String item : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            if (item.isEmpty()) continue;
            String[] pair = item.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            fields.put(key, value);
        }
        return fields;
    }

    private static void serveAsset(HttpExchange exchange, String path) throws IOException {
        String mimeType = path.endsWith(".css") ? "text/css" : path.endsWith(".js")
                ? "text/javascript" : "text/html";
        try (InputStream asset = QueueServer.class.getResourceAsStream("/public" + path)) {
            if (asset == null) {
                send(exchange, 404, "application/json", "{\"error\":\"Not found\"}");
                return;
            }
            byte[] bytes = asset.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", mimeType + "; charset=utf-8");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }
    }

    private static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(code, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
