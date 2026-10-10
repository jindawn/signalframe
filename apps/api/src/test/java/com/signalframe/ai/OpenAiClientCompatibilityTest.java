package com.signalframe.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.ModelProfile;
import com.signalframe.infrastructure.ai.providers.*;
import java.util.UUID;
import org.junit.jupiter.api.*;

/**
 * Client-side compatibility of the OpenAI-compatible adapter, verified against
 * the local stub server: no external call and no credential are involved.
 *
 * <p>Three things are pinned here because a gateway can legitimately require them
 * and because getting them wrong is invisible until a live run fails:
 *
 * <ol>
 *   <li><b>Honest identity.</b> The request identifies this runtime as SignalFrame.
 *       It never claims to be OpenCode, Claude Code, curl or a browser.</li>
 *   <li><b>Stable session correlation.</b> One analysis has one session id across
 *       its transport retry, its repair round and (in the staged topology) every
 *       stage, and two analyses never share one.</li>
 *   <li><b>Reasoning-tolerant parsing.</b> A reasoning model returns
 *       {@code reasoning_content} next to {@code content} and reports
 *       {@code completion_tokens_details.reasoning_tokens}. Reason is never
 *       mistaken for the answer, and the reasoning tokens are accounted for as
 *       part of the completion count, not on top of it.</li>
 * </ol>
 */
class OpenAiClientCompatibilityTest {

  final OpenAiCompatibleGateway gateway = new OpenAiCompatibleGateway();

  static ModelCredentials credentials() {
    return new ModelCredentials("stub-key-not-a-real-secret");
  }

  static ModelResponse callWith(
    OpenAiCompatibleGateway gateway,
    OpenAiStubServer stub,
    UUID jobId,
    boolean repair
  ) {
    var profile = AiTestFixtures.liveProfile("stub-model", stub.baseUrl(), 20);
    return gateway.call(
      AiTestFixtures.request(
        profile,
        AiTestFixtures.news(),
        "produce analysis json",
        jobId,
        repair
      ),
      credentials()
    );
  }

  // ---- identity ------------------------------------------------------------

  @Test
  void everyRequestIdentifiesItselfAsSignalFrameAndNeverAsAnotherClient()
    throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(OpenAiStubServer.completion("{\"ok\":true}", 3, 2))
      )
    ) {
      callWith(gateway, stub, UUID.randomUUID(), false);

      var agent = stub.header(0, "User-Agent");
      assertNotNull(agent, "the User-Agent must reach the provider");
      assertTrue(
        agent.startsWith("SignalFrame/"),
        "the client must name this runtime: " + agent
      );
      var lower = agent.toLowerCase(java.util.Locale.ROOT);
      for (var impersonated : java.util.List.of(
        "opencode",
        "claude",
        "anthropic",
        "curl",
        "mozilla",
        "python-requests",
        "okhttp"
      )) {
        assertFalse(
          lower.contains(impersonated),
          "the client must not impersonate " + impersonated + ": " + agent
        );
      }
    }
  }

  @Test
  void theShippedDefaultsAreSignalFrameIdentityAndTheSessionHeader()
    throws Exception {
    var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
      .load(
        "application.yml",
        new org.springframework.core.io.ClassPathResource("application.yml")
      );
    var propertySource = sources.getFirst();
    var userAgent = String.valueOf(
      propertySource.getProperty("ai.client.user-agent")
    );
    var sessionHeader = String.valueOf(
      propertySource.getProperty("ai.client.session-header-name")
    );
    assertTrue(
      userAgent.contains(OpenAiCompatibleGateway.DEFAULT_USER_AGENT),
      "the shipped default must identify this runtime: " + userAgent
    );
    assertTrue(
      sessionHeader.contains(OpenAiCompatibleGateway.DEFAULT_SESSION_HEADER),
      "the shipped default must carry the session header name: " + sessionHeader
    );
    assertEquals(
      "true",
      String.valueOf(
        propertySource.getProperty("ai.client.session-header-enabled")
      ).replaceAll(".*:", "").replace("}", ""),
      "the session header is on by default, and can be switched off by config"
    );
  }

  @Test
  void theClientIdentityIsConfigurableWithoutCodeChanges() throws Exception {
    var custom = new OpenAiCompatibleGateway(
      "SignalFrame/9.9.9 (staging)",
      "x-opencode-session",
      true
    );
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(OpenAiStubServer.completion("{}", 1, 1))
      )
    ) {
      callWith(custom, stub, UUID.randomUUID(), false);
      assertEquals(
        "SignalFrame/9.9.9 (staging)",
        stub.header(0, "User-Agent")
      );
    }
  }

  // ---- session correlation --------------------------------------------------

  @Test
  void oneAnalysisKeepsOneSessionAcrossTransportRetryAndRepair()
    throws Exception {
    var jobId = UUID.randomUUID();
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(OpenAiStubServer.completion("{\"ok\":true}", 3, 2))
      )
    ) {
      // The first attempt, the transport retry and the single repair round all
      // replay the same job id, which is how the application layer drives them.
      callWith(gateway, stub, jobId, false);
      callWith(gateway, stub, jobId, false);
      callWith(gateway, stub, jobId, true);

      var sessions = java.util.List.of(
        stub.header(0, "x-opencode-session"),
        stub.header(1, "x-opencode-session"),
        stub.header(2, "x-opencode-session")
      );
      assertNotNull(sessions.getFirst(), "the session header must be sent");
      assertEquals(
        1,
        new java.util.HashSet<>(sessions).size(),
        "retry and repair must reuse the session: " + sessions
      );
      assertEquals(
        OpenAiCompatibleGateway.sessionId(jobId),
        sessions.getFirst(),
        "the session id is derived from the job, not generated per call"
      );
    }
  }

  @Test
  void twoAnalysesNeverShareASession() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(OpenAiStubServer.completion("{}", 1, 1))
      )
    ) {
      var first = UUID.randomUUID();
      var second = UUID.randomUUID();
      callWith(gateway, stub, first, false);
      callWith(gateway, stub, second, false);
      assertNotEquals(
        stub.header(0, "x-opencode-session"),
        stub.header(1, "x-opencode-session")
      );
      assertNotEquals(
        OpenAiCompatibleGateway.sessionId(first),
        OpenAiCompatibleGateway.sessionId(second)
      );
    }
  }

  @Test
  void theSessionHeaderIsOpaqueAndNotTheInternalJobId() {
    var jobId = UUID.randomUUID();
    var session = OpenAiCompatibleGateway.sessionId(jobId);
    assertNotEquals(jobId.toString(), session);
    assertDoesNotThrow(() -> UUID.fromString(session));
    assertEquals(session, OpenAiCompatibleGateway.sessionId(jobId));
  }

  @Test
  void theSessionHeaderCanBeDisabledOrRenamedByConfiguration() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(OpenAiStubServer.completion("{}", 1, 1))
      )
    ) {
      var disabled = new OpenAiCompatibleGateway(
        OpenAiCompatibleGateway.DEFAULT_USER_AGENT,
        "x-opencode-session",
        false
      );
      callWith(disabled, stub, UUID.randomUUID(), false);
      assertNull(
        stub.header(0, "x-opencode-session"),
        "a deployment that does not want the session header gets none"
      );

      var renamed = new OpenAiCompatibleGateway(
        OpenAiCompatibleGateway.DEFAULT_USER_AGENT,
        "x-session-id",
        true
      );
      callWith(renamed, stub, UUID.randomUUID(), false);
      assertNotNull(stub.header(1, "x-session-id"));
      assertNull(stub.header(1, "x-opencode-session"));
    }
  }

  // ---- reasoning models -----------------------------------------------------

  @Test
  void reasoningContentIsNeverMistakenForTheAnswer() throws Exception {
    var answer = "{\"summary\":\"the answer\"}";
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(
          OpenAiStubServer.reasoningCompletion(
            answer,
            "Let me think about this. The user wants JSON. I will produce it.",
            81,
            89,
            95,
            "stop"
          )
        )
      )
    ) {
      var response = callWith(gateway, stub, UUID.randomUUID(), false);
      assertEquals(
        answer,
        response.content(),
        "only message.content is the completion; reasoning_content must not leak into it"
      );
      assertFalse(response.content().contains("Let me think"));
    }
  }

  @Test
  void reasoningTokensAreInsideTheCompletionCountAndUsageStaysConsistent()
    throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(
          OpenAiStubServer.reasoningCompletion("{}", "reasoning", 81, 89, 95, "stop")
        )
      )
    ) {
      var usage = callWith(gateway, stub, UUID.randomUUID(), false).usage();
      assertEquals(81L, usage.inputTokens().longValue());
      assertEquals(95L, usage.outputTokens().longValue());
      assertEquals(176L, usage.totalTokens().longValue());
      assertEquals(
        usage.inputTokens() + usage.outputTokens(),
        usage.totalTokens().longValue(),
        "reasoning tokens are part of completion_tokens, so the totals still add up"
      );
      assertNull(usage.estimatedCost(), "cost stays unknown, never fabricated");
    }
  }

  /**
   * A reasoning-only reply carries no answer. Reporting it as the completion
   * would feed the validator a body that cannot be an AnalysisResult and would
   * misattribute the failure; it is a provider response failure.
   */
  @Test
  void aReasoningOnlyReplyIsAProviderResponseFailure() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(
          OpenAiStubServer.reasoningOnlyCompletion("thinking, but no answer")
        )
      )
    ) {
      var failure = assertThrows(ModelInvocationException.class, () ->
        callWith(gateway, stub, UUID.randomUUID(), false)
      );
      assertEquals("PROVIDER_RESPONSE_INVALID", failure.code());
    }
  }

  /** {@code finish_reason=length} is surfaced as content, never as a silent pass. */
  @Test
  void aLengthTruncatedReplyIsReturnedVerbatimForValidationToReject()
    throws Exception {
    var truncated = "{\"summary\":\"half a snapshot\"";
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(
          OpenAiStubServer.reasoningCompletion(
            truncated,
            "reasoning",
            81,
            4000,
            4096,
            "length"
          )
        )
      )
    ) {
      var response = callWith(gateway, stub, UUID.randomUUID(), false);
      assertEquals(
        truncated,
        response.content(),
        "a truncated body is still the completion; validation decides"
      );
    }
  }

  // ---- request shape --------------------------------------------------------

  @Test
  void theRequestShapeStaysTheConfiguredJsonObjectCall() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(OpenAiStubServer.completion("{}", 1, 1))
      )
    ) {
      callWith(gateway, stub, UUID.randomUUID(), false);
      var sent = stub.bodies().getFirst();
      assertTrue(sent.contains("\"model\":\"stub-model\""), sent);
      assertTrue(sent.contains("json_object"), sent);
      assertTrue(sent.contains("\"temperature\":0.2"), sent);
      assertTrue(sent.contains("\"max_tokens\":2048"), sent);
      assertFalse(
        sent.contains("\"stream\":true"),
        "the runtime never requests a streamed completion: " + sent
      );
      assertFalse(
        sent.contains("stub-key-not-a-real-secret"),
        "the credential never appears in the request body"
      );
      assertEquals("/chat/completions", stub.paths().getFirst());
      assertTrue(
        stub.authorizations().getFirst().contains("stub-key-not-a-real-secret"),
        "the credential is sent as a bearer token, not in the body"
      );
    }
  }
}
