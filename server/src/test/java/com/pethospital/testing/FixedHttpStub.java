package com.pethospital.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class FixedHttpStub implements AutoCloseable {
    public static final String RATE_LIMITED_PATH = "/responses/429";
    public static final String INTERNAL_ERROR_PATH = "/responses/500";
    public static final String UNAVAILABLE_PATH = "/responses/503";

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    private FixedHttpStub(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static FixedHttpStub start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        FixedHttpStub stub = new FixedHttpStub(server, executor);
        server.setExecutor(executor);
        stub.addResponse(RATE_LIMITED_PATH, 429, "RATE_LIMITED", "stub rate limit", "2");
        stub.addResponse(INTERNAL_ERROR_PATH, 500, "STUB_INTERNAL_ERROR", "stub internal error", null);
        stub.addResponse(UNAVAILABLE_PATH, 503, "DEPENDENCY_UNAVAILABLE", "stub unavailable", null);
        server.start();
        return stub;
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public int requestCount(String path) {
        AtomicInteger count = requestCounts.get(path);
        return count == null ? 0 : count.get();
    }

    private void addResponse(String path, int status, String code, String message, String retryAfter) {
        requestCounts.put(path, new AtomicInteger());
        server.createContext(path, exchange -> {
            requestCounts.get(path).incrementAndGet();
            if (retryAfter != null) {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            byte[] body = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
            write(exchange, status, body);
        });
    }

    private static void write(HttpExchange exchange, int status, byte[] body) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
