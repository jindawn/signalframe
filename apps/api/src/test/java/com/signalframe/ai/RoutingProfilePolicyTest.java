package com.signalframe.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.ai.domain.*;
import com.signalframe.contract.ModelProfile;
import com.signalframe.infrastructure.ai.providers.*;
import com.signalframe.shared.ApplicationException;
import com.signalframe.shared.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.*;

class RoutingProfilePolicyTest {

  final JsonCodec json = new JsonCodec();

  static ModelProfile profile(
    String provider,
    String baseUrl,
    boolean toolCalling
  ) {
    return new ModelProfile(
      provider,
      "some-model",
      baseUrl,
      "AI_TEST_API_KEY",
      0.2,
      2048,
      20,
      true,
      toolCalling,
      true
    );
  }

  @Test
  void configuredProvidersAreAccepted() {
    var policy = new RoutingProfilePolicy();
    assertDoesNotThrow(() ->
      policy.validate(profile("mock", "", false))
    );
    assertDoesNotThrow(() ->
      policy.validate(profile("openai-compatible", "https://api.example.com", false))
    );
  }

  @Test
  void unknownProviderIsRejectedWithACleanError() {
    var policy = new RoutingProfilePolicy();
    var error = assertThrows(ApplicationException.class, () ->
      policy.validate(profile("unregistered", "https://api.example.com", false))
    );
    assertEquals(400, error.status());
    assertEquals("UNSUPPORTED_PROVIDER", error.code());
    assertTrue(error.getMessage().contains("mock"));
  }

  @Test
  void providersAreDerivedFromRegisteredAdapters() {
    var custom = new ModelProviderAdapter() {
      @Override
      public String provider() {
        return "custom-remote";
      }

      @Override
      public ModelResponse call(
        ModelRequest request,
        ModelCredentials credential
      ) {
        throw new UnsupportedOperationException();
      }
    };
    var policy = new RoutingProfilePolicy(
      new EnvironmentCredentialSource(),
      List.of(new MockModelGateway(json), custom)
    );
    assertDoesNotThrow(() ->
      policy.validate(profile("custom-remote", "https://api.example.com", false))
    );
    var error = assertThrows(ApplicationException.class, () ->
      policy.validate(profile("openai-compatible", "https://api.example.com", false))
    );
    assertEquals("UNSUPPORTED_PROVIDER", error.code());
  }

  @Test
  void toolCallingIsExplicitlyRejected() {
    var policy = new RoutingProfilePolicy();
    var error = assertThrows(ApplicationException.class, () ->
      policy.validate(profile("mock", "", true))
    );
    assertEquals("UNSUPPORTED_CAPABILITY", error.code());
  }

  @Test
  void liveEndpointMustBeTlsOrLoopbackHttpWithoutEmbeddedCredentials() {
    var policy = new RoutingProfilePolicy();
    for (var rejected : List.of(
      "http://api.example.com",
      "https://user:pass@api.example.com",
      "https://api.example.com?key=value",
      "http://127.0.0.1.example.com",
      "http://10.0.0.5:11434/v1",
      "",
      "not-a-url"
    )) assertEquals(
      "INVALID_PROFILE",
      assertThrows(ApplicationException.class, () ->
        policy.validate(profile("openai-compatible", rejected, false))
      ).code(),
      "should reject " + rejected
    );
    for (var accepted : List.of(
      "https://api.example.com",
      "http://127.0.0.1:11434/v1",
      "http://localhost:1234/v1",
      "http://[::1]:8080/v1"
    )) assertDoesNotThrow(
      () -> policy.validate(profile("openai-compatible", accepted, false)),
      "should accept " + accepted
    );
  }

  @Test
  void credentialFreeProfilesDoNotNeedAUrlPolicy() {
    var policy = new RoutingProfilePolicy();
    assertDoesNotThrow(() ->
      policy.validate(profile("mock", "", false))
    );
    assertDoesNotThrow(() ->
      policy.validate(profile("mock", "http://example.com/v1", false))
    );
  }

  @Test
  void effectiveModeReflectsCredentialPresenceWithoutRevealingIt() {
    var profile = profile("openai-compatible", "https://api.example.com", false);
    var unconfigured = new RoutingProfilePolicy(
      name -> Optional.empty(),
      List.of(new OpenAiCompatibleGateway())
    );
    assertEquals("MOCK", unconfigured.effectiveMode(profile));
    var configured = new RoutingProfilePolicy(
      name -> Optional.of("value-never-returned"),
      List.of(new OpenAiCompatibleGateway())
    );
    assertEquals("LIVE", configured.effectiveMode(profile));
  }

  @Test
  void credentialFreeProfileIsAlwaysMockAndDisabledProfileIsDisabled() {
    var policy = new RoutingProfilePolicy(
      name -> Optional.of("unused"),
      List.of(new MockModelGateway(json), new OpenAiCompatibleGateway())
    );
    assertEquals("MOCK", policy.effectiveMode(profile("mock", "", false)));
    var disabled = new ModelProfile(
      "mock",
      "some-model",
      "",
      "AI_TEST_API_KEY",
      0.2,
      2048,
      20,
      true,
      false,
      false
    );
    assertEquals("DISABLED", policy.effectiveMode(disabled));
  }

  @Test
  void environmentSourceReadsNamesAndIgnoresMissingOnes() {
    var source = new EnvironmentCredentialSource();
    assertTrue(source.resolve("PATH").isPresent());
    assertTrue(source.resolve("SIGNALFRAME_TEST_ABSENT_VARIABLE_9F3A").isEmpty());
    assertTrue(source.resolve(null).isEmpty());
    assertTrue(source.resolve("  ").isEmpty());
  }
}
