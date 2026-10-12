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

  /** A configured value and the environment variable that can override it. */
  record Setting(String value, String overrideVariable) {}

  static String rawSetting(String key) {
    try {
      var sources = new YamlPropertySourceLoader().load(
        "application.yml",
        new ClassPathResource("application.yml")
      );
      assertFalse(sources.isEmpty(), "application.yml must load");
      var raw = sources.getFirst().getProperty(key);
      assertNotNull(raw, key + " must be configured");
      return raw.toString().trim();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("application.yml is unreadable", e);
    }
  }

  /**
   * Resolves {@code ${VAR:default}} the way the deployment expects: the value is
   * the documented default, and the variable name is the deployment's override.
   */
  static Setting setting(String key) {
    var raw = rawSetting(key);
    if (raw.startsWith("${") && raw.endsWith("}")) {
      var body = raw.substring(2, raw.length() - 1);
      int colon = body.indexOf(':');
      assertTrue(colon > 0, key + " must be ${VAR:default}: " + raw);
      return new Setting(body.substring(colon + 1).trim(), body.substring(0, colon).trim());
    }
    return new Setting(raw, null);
  }

  static int configuredSynthesisMaxTokens() {
    return Integer.parseInt(setting("ai.profiles[analysis.fast].max-tokens").value());
  }

  @Test
  void theConfiguredSynthesisCeilingLeavesRoomForReasoning() {
    var maxTokens = configuredSynthesisMaxTokens();
    assertTrue(
      maxTokens >= 8192,
      "the synthesis ceiling must cover a reply plus its reasoning, was " + maxTokens
    );
  }

  /**
   * The three knobs the metered rehearsal depends on must be overridable by
   * environment without editing the file, and must keep their compatible defaults.
   */
  @Test
  void theBudgetsAndRetryPolicyAreConfigurableWithCompatibleDefaults() {
    var ceiling = setting("ai.profiles[analysis.fast].max-tokens");
    assertEquals("AI_FAST_MAX_TOKENS", ceiling.overrideVariable());
    assertEquals("8192", ceiling.value(), "the compatible default ceiling");

    var timeout = setting("ai.profiles[analysis.fast].timeout");
    assertEquals("AI_FAST_TIMEOUT", timeout.overrideVariable());
    assertEquals("45", timeout.value(), "the compatible default bound");

    var attempts = setting("ai.retry.max-attempts");
    assertEquals("AI_RETRY_MAX_ATTEMPTS", attempts.overrideVariable());
    assertEquals("2", attempts.value(), "the compatible default attempt count");

    var repairs = setting("ai.retry.max-repair-attempts");
    assertEquals("AI_RETRY_MAX_REPAIR_ATTEMPTS", repairs.overrideVariable());
    assertEquals("1", repairs.value(), "the compatible default repair count");
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
