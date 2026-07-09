package org.example.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.example.trade.CreateOrderRequest;
import org.example.trade.OrderPlacementResult;
import org.example.trade.OrderService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

public final class TradingHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final OrderService orderService;
    private boolean started;

    public TradingHttpServer(int port) throws IOException {
        this.orderService = new OrderService();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", new RootHandler());
        this.server.createContext("/api", new ApiHandler());
    }

    public void start() {
        if (!started) {
            server.start();
            started = true;
        }
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
        started = false;
    }

    @Override
    public void close() {
        stop();
    }

    private final class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/".equals(path)) {
                sendHtml(exchange, 200, readResource("/static/index.html"));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/static/")) {
                sendStatic(exchange, path);
                return;
            }
            sendJson(exchange, 404, JsonCodec.errorJson("not found"));
        }
    }

    private final class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                dispatch(exchange);
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, JsonCodec.errorJson(e.getMessage()));
            } catch (IllegalStateException e) {
                sendJson(exchange, 409, JsonCodec.errorJson(e.getMessage()));
            } catch (NoSuchElementException e) {
                sendJson(exchange, 404, JsonCodec.errorJson(e.getMessage()));
            } catch (Exception e) {
                sendJson(exchange, 500, JsonCodec.errorJson("internal server error"));
            }
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = exchange.getRequestURI().getPath();
        String suffix = path.length() > 4 ? path.substring(4) : "";

        if ("/orders".equals(suffix)) {
            if ("POST".equals(method)) {
                handleCreateOrder(exchange);
                return;
            }
            if ("GET".equals(method)) {
                sendJson(exchange, 200, JsonCodec.ordersResponseJson(orderService.listOrders()));
                return;
            }
        }

        if (suffix.startsWith("/orders/")) {
            String tail = suffix.substring("/orders/".length());
            if (tail.endsWith("/cancel")) {
                if ("POST".equals(method)) {
                    handleCancelOrder(exchange, tail.substring(0, tail.length() - "/cancel".length()));
                    return;
                }
            } else if ("GET".equals(method)) {
                handleGetOrder(exchange, tail);
                return;
            }
        }

        if ("/trades".equals(suffix) && "GET".equals(method)) {
            sendJson(exchange, 200, JsonCodec.tradesResponseJson(orderService.listTrades()));
            return;
        }

        if ("/health".equals(suffix) && "GET".equals(method)) {
            sendJson(exchange, 200, JsonCodec.okJson("ok"));
            return;
        }

        if ("OPTIONS".equals(method)) {
            exchange.getResponseHeaders().add("Allow", "GET,POST,OPTIONS");
            sendJson(exchange, 204, "");
            return;
        }

        sendJson(exchange, 404, JsonCodec.errorJson("not found"));
    }

    private void handleCreateOrder(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        CreateOrderRequest request = JsonCodec.parseCreateOrderRequest(body);
        OrderPlacementResult result = orderService.placeOrder(request);
        sendJson(exchange, 201, JsonCodec.placementResponseJson(result));
    }

    private void handleGetOrder(HttpExchange exchange, String rawId) throws IOException {
        long orderId = parseLong(rawId, "order id");
        sendJson(exchange, 200, JsonCodec.orderJson(orderService.getOrder(orderId)));
    }

    private void handleCancelOrder(HttpExchange exchange, String rawId) throws IOException {
        long orderId = parseLong(rawId, "order id");
        sendJson(exchange, 200, JsonCodec.orderJson(orderService.cancelOrder(orderId)));
    }

    private long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        if (statusCode == 204) {
            exchange.sendResponseHeaders(statusCode, -1);
        } else {
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
        exchange.close();
    }

    private void sendHtml(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        exchange.close();
    }

    private void sendStatic(HttpExchange exchange, String path) throws IOException {
        String normalized = path.substring("/static/".length());
        if (normalized.isBlank() || normalized.contains("..")) {
            sendJson(exchange, 400, JsonCodec.errorJson("bad static path"));
            return;
        }
        String contentType = contentType(normalized);
        String resourcePath = "/static/" + normalized;
        String body = readResource(resourcePath);
        if (body == null) {
            sendJson(exchange, 404, JsonCodec.errorJson("not found"));
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        exchange.close();
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream input = TradingHttpServer.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return null;
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String contentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
