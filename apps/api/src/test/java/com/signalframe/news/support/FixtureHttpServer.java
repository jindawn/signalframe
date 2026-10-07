package com.signalframe.news.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Deterministic local HTTP server for ingestion tests.
 *
 * <p>Tests must not depend on public news sites, so every network scenario
 * (redirect, oversized body, slow response, blocked status, content type) is
 * served from this loopback fixture. Only test data is served.
 */
public final class FixtureHttpServer implements AutoCloseable {

  /** One scripted response. */
  public record Response(
    int status,
    String contentType,
    byte[] body,
    Map<String, String> headers,
    Duration delay
  ) {
    public static Response html(String body) {
      return html(body, Duration.ZERO);
    }

    public static Response html(String body, Duration delay) {
      return new Response(
        200,
        "text/html; charset=utf-8",
        body.getBytes(StandardCharsets.UTF_8),
        Map.of(),
        delay
      );
    }

    public static Response text(String body) {
      return new Response(
        200,
        "text/plain; charset=utf-8",
        body.getBytes(StandardCharsets.UTF_8),
        Map.of(),
        Duration.ZERO
      );
    }

    public static Response status(int status, String contentType, String body) {
      return new Response(
        status,
        contentType,
        body.getBytes(StandardCharsets.UTF_8),
        Map.of(),
        Duration.ZERO
      );
    }

    public static Response redirect(String location) {
      return new Response(302, "text/plain", new byte[0], Map.of("Location", location), Duration.ZERO);
    }

    public static Response bytes(
      int status,
      String contentType,
      byte[] body,
      Map<String, String> headers
    ) {
      return new Response(status, contentType, body, headers, Duration.ZERO);
    }
  }

  public record ReceivedRequest(String path, Map<String, String> headers) {
    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  private final HttpServer server;
  private final Map<String, Response> routes = new ConcurrentHashMap<>();
  private final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();

  public FixtureHttpServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(
      Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "fixture-http");
        thread.setDaemon(true);
        return thread;
      })
    );
    server.start();
  }

  public void route(String path, Response response) {
    routes.put(path, response);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public String url(String path) {
    return "http://127.0.0.1:" + port() + path;
  }

  public List<ReceivedRequest> requests() {
    return List.copyOf(received);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    received.add(new ReceivedRequest(path, lowercaseHeaders(exchange)));
    Response response = routes.get(path);
    if (response == null) {
      respond(
        exchange,
        new Response(404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), Map.of(), Duration.ZERO)
      );
      return;
    }
    if (!response.delay().isZero()) {
      try {
        Thread.sleep(response.delay().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    try {
      respond(exchange, response);
    } catch (IOException clientGone) {
      exchange.close(); // the client aborted (e.g. once a fetch limit tripped)
    }
  }

  private static Map<String, String> lowercaseHeaders(HttpExchange exchange) {
    Map<String, String> headers = new ConcurrentHashMap<>();
    exchange
      .getRequestHeaders()
      .forEach((name, values) ->
        headers.put(
          name.toLowerCase(Locale.ROOT),
          values.isEmpty() ? "" : values.get(0)
        )
      );
    return headers;
  }

  private static void respond(HttpExchange exchange, Response response)
    throws IOException {
    exchange.getResponseHeaders().set("Content-Type", response.contentType());
    response.headers().forEach((name, value) ->
      exchange.getResponseHeaders().set(name, value)
    );
    byte[] body = response.body() == null ? new byte[0] : response.body();
    if (body.length == 0) {
      exchange.sendResponseHeaders(response.status(), -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(response.status(), body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
