package com.signalframe.ai.application;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.ModelPurpose;
import com.signalframe.infrastructure.ai.providers.MockModelGateway;
import com.signalframe.shared.JsonCodec;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * The completion ceiling has to hold the reply <em>and</em> the reasoning that
 * precedes it.
 *
 * <p>A reasoning model behind an OpenAI-compatible gateway reports reasoning
 * tokens inside {@code completion_tokens}, so a ceiling sized for the JSON alone
 * leaves the reply truncated mid-object on any non-trivial prompt. Truncated JSON
 * is then rejected by the schema gate, which looks like a model defect and is
 * expensive to diagnose. These tests pin the configured ceiling and the headroom
 * the reference snapshot leaves inside it, so a configuration change cannot
 * silently starve the completion.
 */
class SynthesisBudgetTest {

  static final JsonCodec JSON = new JsonCodec();

  /**
   * The smallest reply the pipeline can produce, which is the floor a budget has
   * to clear. Deliberately the offline snapshot: it is deterministic, so the
   * headroom assertion below never flaps.
   */
  static String referenceCompletion() {
    return new MockModelGateway(JSON)
      .call(
        new ModelRequest(
          UUID.randomUUID(),
          ModelPurpose.SYNTHESIS,
          "synthesis-v3",
          "",
          com.signalframe.ai.AiTestFixtures.news(),
          com.signalframe.ai.AiTestFixtures.profile("mock", "mock-v1"),
          false
        )
      )
      .content();
  }

  /** Conservative ASCII-JSON estimate; the exact tokenizer is the provider's. */
  static int estimatedTokens(String text) {
    return text.length() / 4;
  }

  static Integer configuredSynthesisMaxTokens() {
    try {
      var sources = new YamlPropertySourceLoader().load(
        "application.yml",
        new ClassPathResource("application.yml")
      );
      assertFalse(sources.isEmpty(), "application.yml must load");
      var raw = sources
        .getFirst()
        .getProperty("ai.profiles[analysis.fast].max-tokens");
      assertNotNull(
        raw,
        "ai.profiles[analysis.fast].max-tokens must be configured"
      );
      return Integer.valueOf(raw.toString().trim());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("application.yml is unreadable", e);
    }
  }

  @Test
  void theConfiguredSynthesisCeilingLeavesRoomForReasoning() {
    var maxTokens = configuredSynthesisMaxTokens();
    assertTrue(
      maxTokens >= 8192,
      "the synthesis ceiling must cover a reply plus its reasoning, was " + maxTokens
    );
  }

  @Test
  void theReferenceCompletionLeavesAtLeastThreeQuartersOfTheCeilingForReasoning() {
    var maxTokens = configuredSynthesisMaxTokens();
    var replyTokens = estimatedTokens(referenceCompletion());
    assertTrue(
      replyTokens > 0,
      "the reference snapshot must not be empty"
    );
    assertTrue(
      replyTokens * 4 <= maxTokens,
      "the reference reply is ~" +
      replyTokens +
      " estimated tokens, which must fit in a quarter of the " +
      maxTokens +
      "-token ceiling so a reasoning model and a verbose real snapshot still fit"
    );
  }

  /** The ceiling is a per-profile setting, so a deployment can still tune it. */
  @Test
  void theCeilingIsAProfileSettingNotAConstant() {
    var profile = new com.signalframe.contract.ModelProfile(
      "openai-compatible",
      "any-model",
      "http://127.0.0.1:1",
      "AI_FAST_API_KEY",
      0.2,
      16000,
      45,
      true,
      false,
      true
    );
    assertEquals(16000, profile.maxTokens());
  }
}
