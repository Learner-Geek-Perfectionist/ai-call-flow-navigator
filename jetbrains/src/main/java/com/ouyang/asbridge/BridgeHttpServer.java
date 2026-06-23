package com.ouyang.asbridge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

final class BridgeHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final int port;

    private BridgeHttpServer(HttpServer server, int port) {
        this.server = server;
        this.port = port;
    }

    static BridgeHttpServer start(BridgeRuntimeConfig config, SourceOpener opener) throws IOException {
        if (!config.enabled()) {
            throw new IllegalArgumentException("bridge is disabled");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port()), 0);
        BridgeHttpServer bridge = new BridgeHttpServer(server, server.getAddress().getPort());
        server.createContext("/", exchange -> bridge.handle(exchange, config, opener));
        server.start();
        return bridge;
    }

    URI uri(String pathAndQuery) {
        return URI.create("http://127.0.0.1:" + port + pathAndQuery);
    }

    int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static boolean isAddressInUse(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BindException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void handle(HttpExchange exchange, BridgeRuntimeConfig config, SourceOpener opener) throws IOException {
        addCorsHeaders(exchange);
        String method = exchange.getRequestMethod();
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

        if ("OPTIONS".equals(method)) {
            sendNoContent(exchange);
            return;
        }

        if ("/health".equals(path)) {
            if (!"GET".equals(method)) {
                sendText(exchange, 405, "Method not allowed");
                return;
            }
            sendText(exchange, 200, "OK");
            return;
        }

        if ("/as-bridge.js".equals(path)) {
            if (!"GET".equals(method)) {
                sendText(exchange, 405, "Method not allowed");
                return;
            }
            sendJavaScript(exchange, BridgeHtmlScript.content());
            return;
        }

        if (!"/open".equals(path)) {
            sendText(exchange, 404, "Unsupported endpoint: " + path);
            return;
        }

        if (!"GET".equals(method) && !"POST".equals(method)) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }

        try {
            OpenRequest request = OpenRequest.parse(uri.toString());
            Path absolutePath = ProjectPathResolver.resolveInsideRoot(
                    Path.of(config.projectRoot()),
                    request.relativePath()
            );
            opener.open(new ResolvedOpenRequest(absolutePath, request.line(), request.column()));
            sendNoContent(exchange);
        } catch (IllegalArgumentException error) {
            sendText(exchange, statusFor(error), error.getMessage());
        }
    }

    private static int statusFor(IllegalArgumentException error) {
        String message = error.getMessage();
        if (message != null && (message.contains("Only relative paths") || message.contains("escapes"))) {
            return 403;
        }
        if (message != null && message.contains("Unsupported endpoint")) {
            return 404;
        }
        return 400;
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().put("Access-Control-Allow-Origin", List.of("*"));
        exchange.getResponseHeaders().put("Access-Control-Allow-Methods", List.of("GET, POST, OPTIONS"));
    }

    private static void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of("text/plain; charset=utf-8"));
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendJavaScript(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", List.of("application/javascript; charset=utf-8"));
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
