package com.signalframe.ai.application;

import com.signalframe.contract.*;
import com.signalframe.shared.ApplicationException;
import jakarta.validation.Validator;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ModelRouter {

  private final AiProperties config;
  private final Map<String, ModelProfile> profiles;
  private final Validator validator;
  private final com.signalframe.ai.domain.ModelProfilePolicy policy;

  public ModelRouter(
    AiProperties config,
    Validator validator,
    com.signalframe.ai.domain.ModelProfilePolicy policy
  ) {
    this.config = config;
    this.policy = policy;
    this.validator = validator;
    this.profiles = new ConcurrentHashMap<>(config.profiles());
    profiles.forEach((n, p) -> validate(p));
  }

  public ModelProfile route(ModelPurpose purpose) {
    String name = config
      .routes()
      .getOrDefault(purpose, config.defaultProfile());
    var p = profiles.get(name);
    if (p == null || !p.enabled()) throw new ApplicationException(
      503,
      "PROFILE_DISABLED",
      "No enabled model profile for purpose"
    );
    return p;
  }

  public List<NamedModelProfile> list() {
    return profiles
      .entrySet()
      .stream()
      .sorted(Map.Entry.comparingByKey())
      .map(e -> named(e.getKey(), e.getValue()))
      .toList();
  }

  public NamedModelProfile update(String name, ModelProfile p) {
    if (!profiles.containsKey(name)) throw ApplicationException.missing();
    validate(p);
    profiles.put(name, p);
    return named(name, p);
  }

  private void validate(ModelProfile p) {
    if (!validator.validate(p).isEmpty()) throw new ApplicationException(
      400,
      "INVALID_PROFILE",
      "Invalid model profile"
    );
    policy.validate(p);
  }

  private NamedModelProfile named(String name, ModelProfile p) {
    return new NamedModelProfile(name, p, policy.effectiveMode(p));
  }
}
