package com.signalframe.infrastructure.ai.providers;

import com.signalframe.ai.domain.ModelProfilePolicy;
import com.signalframe.contract.ModelProfile;
import com.signalframe.shared.ApplicationException;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RoutingProfilePolicy implements ModelProfilePolicy {

  public void validate(ModelProfile p) {
    if (
      !Set.of("mock", "openai-compatible").contains(p.provider())
    ) throw new ApplicationException(
      400,
      "UNSUPPORTED_PROVIDER",
      "Available providers: mock, openai-compatible"
    );
    if (p.toolCalling()) throw new ApplicationException(
      400,
      "UNSUPPORTED_CAPABILITY",
      "Tool calling is reserved for a later adapter"
    );
    if (!p.provider().equals("mock")) {
      try {
        var u = java.net.URI.create(p.baseUrl());
        if (
          !"https".equals(u.getScheme()) ||
          u.getHost() == null ||
          u.getUserInfo() != null ||
          u.getQuery() != null
        ) throw new Exception();
      } catch (Exception e) {
        throw new ApplicationException(
          400,
          "INVALID_PROFILE",
          "Live model base URL must be HTTPS without embedded credentials"
        );
      }
    }
  }

  public String effectiveMode(ModelProfile p) {
    if (!p.enabled()) return "DISABLED";
    String key = System.getenv(p.apiKeyEnv());
    return p.provider().equals("mock") || key == null || key.isBlank()
      ? "MOCK"
      : "LIVE";
  }
}
