package com.signalframe.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.ModelProfile;
import com.signalframe.infrastructure.ai.providers.*;
import com.signalframe.shared.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Provider behaviour verified against a local OpenAI-compatible stub server.
 * No paid key and no external network call is involved.
 */
class OpenAiCompatibleProviderTest {

  final JsonCodec json = new JsonCodec();
  final OpenAiCompatibleGateway gateway = new OpenAiCompatibleGateway();

  static ModelCredentials credentials() {
    return new ModelCredentials("test-key-not-a-real-secret");
  }

  @Test
  void callsConfiguredEndpointAndMapsTextAndUsage() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(
          OpenAiStubServer.completion("{\"ok\":true}", 11, 7)
        )
      )
    ) {
      var profile = AiTestFixtures.liveProfile(
        "stub-model-a",
        stub.baseUrl(),
        20
      );
      var response = gateway.call(
        AiTestFixtures.request(profile, AiTestFixtures.news()),
        credentials()
      );
      assertEquals("{\"ok\":true}", response.content());
      assertEquals("openai-compatible", response.provider());
      assertEquals("stub-model-a", response.model());
      assertEquals(11L, response.usage().inputTokens().longValue());
      assertEquals(7L, response.usage().outputTokens().longValue());
      assertEquals(18L, response.usage().totalTokens().longValue());
      assertNull(
        response.usage().estimatedCost(),
        "cost is unknown, never fabricated"
      );
      String sent = stub.bodies().getFirst();
      assertTrue(sent.contains("\"model\":\"stub-model-a\""), sent);
      assertTrue(sent.contains("json_object"), sent);
      assertTrue(
        sent.contains("\"temperature\":0.2"),
        "configured temperature is sent: " + sent
      );
      assertFalse(stub.paths().isEmpty());
      assertTrue(
        stub.authorizations().getFirst().contains(credentials().value()),
        "configured credential is sent as a bearer token"
      );
    }
  }

  @Test
  void twoConfiguredProfilesReachTheirOwnModel() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(OpenAiStubServer.completion("reply", 1, 1))
      )
    ) {
      var fast = AiTestFixtures.liveProfile(
        "stub-model-fast",
        stub.baseUrl(),
        20
      );
      var deep = AiTestFixtures.liveProfile(
        "stub-model-deep",
        stub.baseUrl(),
        20
      );
      var routing = routing(stub.baseUrl(), credentials().value());
      var news = AiTestFixtures.news();
      var first = routing.call(AiTestFixtures.request(fast, news));
      var second = routing.call(AiTestFixtures.request(deep, news));
      assertEquals("stub-model-fast", first.model());
      assertEquals("stub-model-deep", second.model());
      assertEquals(2, stub.bodies().size());
      assertTrue(
        stub.bodies().get(0).contains("\"model\":\"stub-model-fast\"")
      );
      assertTrue(
        stub.bodies().get(1).contains("\"model\":\"stub-model-deep\"")
      );
      assertEquals(2, stub.authorizations().size());
    }
  }

  @Test
  void missingCredentialFallsBackToCredentialFreeAdapter() {
    var routing = new RoutingModelGateway(
      List.of(new MockModelGateway(json), gateway),
      name -> Optional.empty()
    );
    var profile = AiTestFixtures.liveProfile(
      "stub-model-a",
      "https://provider.invalid",
      20
    );
    var response = routing.call(
      AiTestFixtures.request(profile, AiTestFixtures.news())
    );
    assertEquals("mock", response.provider());
    assertEquals("mock-v1", response.model());
  }

  @Test
  void authenticationFailureIsClassifiedAndNotRetryable() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.status(401, OpenAiStubServer.error(401, "bad key"))
      )
    ) {
      var profile = AiTestFixtures.liveProfile(
        "stub-model-a",
        stub.baseUrl(),
        20
      );
      var failure = assertThrows(ModelInvocationException.class, () ->
        gateway.call(AiTestFixtures.request(profile, AiTestFixtures.news()), credentials())
      );
      assertEquals(ModelFailure.AUTHENTICATION, failure.failure());
      assertEquals("AUTHENTICATION", failure.code());
      assertFalse(failure.retryable());
      assertFalse(failure.getMessage().contains(credentials().value()));
    }
  }

  @Test
  void throttlingAndServerErrorsAreRetryable() throws Exception {
    assertEquals(
      ModelFailure.RATE_LIMITED,
      failureFor(429, true).failure()
    );
    assertEquals(
      ModelFailure.PROVIDER_UNAVAILABLE,
      failureFor(503, true).failure()
    );
    assertEquals(
      ModelFailure.INVALID_REQUEST,
      failureFor(400, false).failure()
    );
    assertEquals(
      ModelFailure.PERMISSION_DENIED,
      failureFor(403, false).failure()
    );
  }

  private ModelInvocationException failureFor(int status, boolean retryable)
    throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.status(
          status,
          OpenAiStubServer.error(status, "stub failure")
        )
      )
    ) {
      var profile = AiTestFixtures.liveProfile(
        "stub-model-a",
        stub.baseUrl(),
        20
      );
      var failure = assertThrows(ModelInvocationException.class, () ->
        gateway.call(AiTestFixtures.request(profile, AiTestFixtures.news()), credentials())
      );
      assertEquals(retryable, failure.retryable(), "status " + status);
      return failure;
    }
  }

  @Test
  void timeoutIsEnforcedAndClassifiedAsRetryable() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.delayed(
          OpenAiStubServer.completion("late", 1, 1),
          8_000
        )
      )
    ) {
      var profile = AiTestFixtures.liveProfile(
        "stub-model-a",
        stub.baseUrl(),
        1
      );
      long started = System.nanoTime();
      var failure = assertThrows(ModelInvocationException.class, () ->
        gateway.call(AiTestFixtures.request(profile, AiTestFixtures.news()), credentials())
      );
      long elapsedMs = (System.nanoTime() - started) / 1_000_000;
      assertEquals(ModelFailure.TIMEOUT, failure.failure());
      assertTrue(failure.retryable());
      assertTrue(
        elapsedMs < 6_000,
        "configured timeout must abort before the stub replies: " + elapsedMs
      );
    }
  }

  @Test
  void emptyCompletionIsRejectedAsInvalidProviderResponse() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok(OpenAiStubServer.completion("", 3, 0))
      )
    ) {
      var profile = AiTestFixtures.liveProfile(
        "stub-model-a",
        stub.baseUrl(),
        20
      );
      var failure = assertThrows(ModelInvocationException.class, () ->
        gateway.call(AiTestFixtures.request(profile, AiTestFixtures.news()), credentials())
      );
      assertEquals(ModelFailure.PROVIDER_RESPONSE_INVALID, failure.failure());
      assertFalse(failure.retryable());
    }
  }

  @Test
  void malformedProviderBodyIsRejected() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok("{\"not\":\"a completion\"}")
      )
    ) {
      var profile = AiTestFixtures.liveProfile(
        "stub-model-a",
        stub.baseUrl(),
        20
      );
      var failure = assertThrows(ModelInvocationException.class, () ->
        gateway.call(AiTestFixtures.request(profile, AiTestFixtures.news()), credentials())
      );
      assertNotNull(failure.failure());
      assertFalse(failure.retryable());
      assertFalse(failure.getMessage().contains(credentials().value()));
    }
  }

  @Test
  void malformedProviderBodyClassificationIsStable() throws Exception {
    try (
      var stub = new OpenAiStubServer(body ->
        OpenAiStubServer.Reply.ok("{\"not\":\"a completion\"}")
      )
    ) {
      var profile = AiTestFixtures.liveProfile(
        "stub-model-a",
        stub.baseUrl(),
        20
      );
      var failure = assertThrows(ModelInvocationException.class, () ->
        gateway.call(AiTestFixtures.request(profile, AiTestFixtures.news()), credentials())
      );
      assertEquals(ModelFailure.PROVIDER_RESPONSE_INVALID, failure.failure());
    }
  }

  @Test
  void missingCredentialIsReportedWithoutCallingTheProvider() {
    var profile = AiTestFixtures.liveProfile(
      "stub-model-a",
      "https://provider.invalid",
      20
    );
    var failure = assertThrows(ModelInvocationException.class, () ->
      gateway.call(AiTestFixtures.request(profile, AiTestFixtures.news()), null)
    );
    assertEquals(ModelFailure.AUTHENTICATION, failure.failure());
  }

  @Test
  void unsupportedProviderIsAConfigurationFailure() {
    var routing = new RoutingModelGateway(
      List.of(new MockModelGateway(json), gateway),
      name -> Optional.of(credentials().value())
    );
    var profile = new ModelProfile(
      "no-such-provider",
      "any",
      "https://provider.invalid",
      "AI_TEST_API_KEY",
      0.2,
      2048,
      20,
      true,
      false,
      true
    );
    var failure = assertThrows(ModelInvocationException.class, () ->
      routing.call(AiTestFixtures.request(profile, AiTestFixtures.news()))
    );
    assertEquals(ModelFailure.CONFIGURATION, failure.failure());
  }

  @Test
  void capabilityRequiredByProfileMustBeProvidedByAdapter() {
    var textOnly = new ModelProviderAdapter() {
      @Override
      public String provider() {
        return "text-only-stub";
      }

      @Override
      public boolean requiresCredentials() {
        return false;
      }

      @Override
      public ModelResponse call(
        ModelRequest request,
        ModelCredentials credential
      ) {
        return new ModelResponse(
          "{}",
          new ModelUsage(null, null, null, null),
          provider(),
          request.profile().model()
        );
      }
    };
    var routing = new RoutingModelGateway(
      List.of(textOnly, new MockModelGateway(json)),
      name -> Optional.empty()
    );
    var profile = new ModelProfile(
      "text-only-stub",
      "any",
      "",
      "AI_TEST_API_KEY",
      0.2,
      2048,
      20,
      true,
      false,
      true
    );
    var failure = assertThrows(ModelInvocationException.class, () ->
      routing.call(AiTestFixtures.request(profile, AiTestFixtures.news()))
    );
    assertEquals(ModelFailure.CAPABILITY, failure.failure());
    assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("structured output"));
  }

  private RoutingModelGateway routing(String baseUrl, String key) {
    return new RoutingModelGateway(
      List.of(new MockModelGateway(json), gateway),
      name -> "AI_TEST_API_KEY".equals(name) ? Optional.of(key) : Optional.empty()
    );
  }
}
