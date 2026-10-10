package com.signalframe.ai;

import com.signalframe.ai.domain.ModelRequest;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import java.time.Instant;
import java.util.UUID;

/** Shared builders for ai runtime tests. Contains no real credential. */
public final class AiTestFixtures {

  private static final JsonCodec JSON = new JsonCodec();

  private AiTestFixtures() {}

  public static NewsItem news() {
    var now = Instant.now();
    return new NewsItem(
      UUID.randomUUID(),
      "Stub announcement",
      new Source(
        UUID.randomUUID(),
        null,
        "一份原始新闻报告，用于验证模型运行时。",
        "PASTED",
        null,
        now
      ),
      DomainType.OTHER,
      now
    );
  }

  public static ModelProfile profile(String provider, String model) {
    return new ModelProfile(
      provider,
      model,
      "",
      "AI_TEST_API_KEY",
      0.2,
      2048,
      20,
      true,
      false,
      true
    );
  }

  public static ModelProfile liveProfile(
    String model,
    String baseUrl,
    int timeoutSeconds
  ) {
    return new ModelProfile(
      "openai-compatible",
      model,
      baseUrl,
      "AI_TEST_API_KEY",
      0.2,
      2048,
      timeoutSeconds,
      true,
      false,
      true
    );
  }

  public static ModelRequest request(
    ModelProfile profile,
    NewsItem news,
    String prompt
  ) {
    return request(profile, news, prompt, UUID.randomUUID(), false);
  }

  /** A request bound to a known job id, so session stability can be asserted. */
  public static ModelRequest request(
    ModelProfile profile,
    NewsItem news,
    String prompt,
    UUID jobId,
    boolean repair
  ) {
    return new ModelRequest(
      jobId,
      ModelPurpose.SYNTHESIS,
      "synthesis-v1",
      prompt,
      news,
      profile,
      repair
    );
  }

  public static ModelRequest request(ModelProfile profile, NewsItem news) {
    return request(profile, news, "produce analysis json");
  }

  /** JSON string literal for embedding a value into a stub response. */
  public static String quote(String value) {
    return JSON.write(value);
  }
}
