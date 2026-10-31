package de.bwjobs.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tiny local web server for tests: serves fixed responses by path and records POST bodies. */
public class MockSite implements AutoCloseable {
    public record Response(int status, String contentType, String body) {}

    public record Request(String method, String path, String query, String contentType, byte[] body, Map<String, String> headers) {
        public String bodyText() { return new String(body, StandardCharsets.ISO_8859_1); }
    }

    private final HttpServer server;
    private final Map<String, Response> routes = new HashMap<>();
    private final Map<String, java.util.function.Function<Request, Response>> dynamic = new HashMap<>();
    public final List<Request> requests = new CopyOnWriteArrayList<>();

    public MockSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public MockSite on(String path, String contentType, String body) {
        routes.put(path, new Response(200, contentType, body));
        return this;
    }

    public MockSite html(String path, String body) {
        return on(path, "text/html; charset=utf-8", body);
    }

    public MockSite json(String path, String body) {
        return on(path, "application/json", body);
    }

    public MockSite dynamic(String path, java.util.function.Function<Request, Response> handler) {
        dynamic.put(path, handler);
        return this;
    }

    public void remove(String path) {
        routes.remove(path);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        byte[] body = ex.getRequestBody().readAllBytes();
        Map<String, String> headers = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        Request req = new Request(ex.getRequestMethod(), path, query, ex.getRequestHeaders().getFirst("Content-Type"), body, headers);
        requests.add(req);
        Response r = dynamic.containsKey(path) ? dynamic.get(path).apply(req) : routes.get(ex.getRequestMethod() + " " + path);
        if (r == null && query != null) r = routes.get(path + "?" + query);
        if (r == null) r = routes.get(path);
        if (r == null) r = new Response(404, "text/plain", "not found");
        byte[] out = r.body().replace("{{BASE}}", base()).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", r.contentType());
        ex.sendResponseHeaders(r.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
