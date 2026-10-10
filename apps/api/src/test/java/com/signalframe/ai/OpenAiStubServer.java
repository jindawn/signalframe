package com.signalframe.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
  private final List<Map<String, String>> headerMaps = new CopyOnWriteArrayList<>();

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

  /** Every header of every received request, keyed case-insensitively by name. */
  List<Map<String, String>> headerMaps() {
    return List.copyOf(headerMaps);
  }

  /** One header of one request, or null when the request did not carry it. */
  String header(int requestIndex, String name) {
    if (requestIndex >= headerMaps.size()) return null;
    var headers = headerMaps.get(requestIndex);
    for (var entry : headers.entrySet()) if (
      entry.getKey().equalsIgnoreCase(name)
    ) return entry.getValue();
    return null;
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

  /**
   * A completion shaped like a reasoning model behind an OpenAI-compatible
   * gateway: the assistant message carries both {@code reasoning_content} and
   * {@code content}, usage breaks the completion count into
   * {@code completion_tokens_details.reasoning_tokens}, and {@code finish_reason}
   * is a real value. The runtime must read {@code content} only and must account
   * for the reasoning tokens inside the reported completion count rather than on
   * top of it.
   */
  static String reasoningCompletion(
    String content,
    String reasoning,
    int promptTokens,
    int reasoningTokens,
    int completionTokens,
    String finishReason
  ) {
    return (
      "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1," +
      "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," +
      "\"content\":" +
      AiTestFixtures.quote(content) +
      ",\"reasoning_content\":" +
      AiTestFixtures.quote(reasoning) +
      "},\"finish_reason\":" +
      AiTestFixtures.quote(finishReason) +
      "}],\"usage\":{\"prompt_tokens\":" +
      promptTokens +
      ",\"completion_tokens\":" +
      completionTokens +
      ",\"total_tokens\":" +
      (promptTokens + completionTokens) +
      ",\"completion_tokens_details\":{\"reasoning_tokens\":" +
      reasoningTokens +
      "},\"prompt_tokens_details\":{\"cached_tokens\":0}}}"
    );
  }

  /** A reasoning-only reply: {@code reasoning_content} set, {@code content} empty. */
  static String reasoningOnlyCompletion(String reasoning) {
    return (
      "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1," +
      "\"model\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," +
      "\"reasoning_content\":" +
      AiTestFixtures.quote(reasoning) +
      "},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5," +
      "\"completion_tokens\":5,\"total_tokens\":10}}"
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
    var received = new java.util.LinkedHashMap<String, String>();
    for (var entry : exchange.getRequestHeaders().entrySet()) {
      received.put(entry.getKey(), String.join(",", entry.getValue()));
    }
    headerMaps.add(Map.copyOf(received));
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
