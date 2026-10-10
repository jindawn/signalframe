package com.signalframe.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Minimal OpenAI-compatible HTTP stub built on the JDK server, so provider
 * tests need no paid key and no extra test dependency.
 */
final class OpenAiStubServer implements AutoCloseable {

  record Reply(int status, String body, long delayMs) {

    static Reply ok(String body) {
      return new Reply(200, body, 0);
    }

    static Reply status(int status, String body) {
      return new Reply(status, body, 0);
    }

    static Reply delayed(String body, long delayMs) {
      return new Reply(200, body, delayMs);
    }
  }

  private final HttpServer server;
  private final Function<String, Reply> responder;
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<String> paths = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();

  OpenAiStubServer(Function<String, Reply> responder) throws IOException {
    this.responder = responder;
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    this.server.createContext("/", this::handle);
    this.server.start();
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  List<String> bodies() {
    return List.copyOf(bodies);
  }

  List<String> paths() {
    return List.copyOf(paths);
  }

  List<String> authorizations() {
    return List.copyOf(authorizations);
  }

  static String completion(
    String content,
    int promptTokens,
    int completionTokens
  ) {
    return (
      "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1," +
      "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," +
      "\"content\":" +
      AiTestFixtures.quote(content) +
      "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" +
      promptTokens +
      ",\"completion_tokens\":" +
      completionTokens +
      ",\"total_tokens\":" +
      (promptTokens + completionTokens) +
      "}}"
    );
  }

  /**
   * A completion whose body carries no usage at all, like some self-hosted
   * OpenAI-compatible servers. The runtime must report unknown, not zeros.
   */
  static String completionWithoutUsage(String content) {
    return (
      "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1," +
      "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," +
      "\"content\":" +
      AiTestFixtures.quote(content) +
      "},\"finish_reason\":\"stop\"}]}"
    );
  }

  /**
   * A completion whose usage omits {@code total_tokens}. Real gateways do this,
   * and the OpenAI client refuses to decode such a body.
   */
  static String completionWithoutTotalUsage(
    String content,
    int promptTokens,
    int completionTokens
  ) {
    return (
      "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1," +
      "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," +
      "\"content\":" +
      AiTestFixtures.quote(content) +
      "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" +
      promptTokens +
      ",\"completion_tokens\":" +
      completionTokens +
      "}}"
    );
  }

  static String error(int status, String message) {
    return (
      "{\"error\":{\"message\":" +
      AiTestFixtures.quote(message) +
      ",\"type\":\"stub_error\",\"code\":\"stub\"}}"
    );
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(
      exchange.getRequestBody().readAllBytes(),
      StandardCharsets.UTF_8
    );
    bodies.add(body);
    paths.add(exchange.getRequestURI().getPath());
    String authorization = exchange
      .getRequestHeaders()
      .getFirst("Authorization");
    authorizations.add(authorization == null ? "" : authorization);
    var reply = responder.apply(body);
    if (reply.delayMs() > 0) {
      try {
        Thread.sleep(reply.delayMs());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(reply.status(), payload.length);
    try (var out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
