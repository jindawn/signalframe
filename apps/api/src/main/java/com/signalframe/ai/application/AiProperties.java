package com.signalframe.ai.application;

import com.signalframe.contract.*;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai")
public record AiProperties(
  String defaultProfile,
  Map<ModelPurpose, String> routes,
  Map<String, ModelProfile> profiles
) {}
