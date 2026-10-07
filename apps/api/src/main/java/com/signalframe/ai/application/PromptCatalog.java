package com.signalframe.ai.application;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PromptCatalog {

  public record VersionedPrompt(String version, String text) {}

  public VersionedPrompt synthesis() {
    try {
      return new VersionedPrompt(
        new ClassPathResource("prompts/synthesis/version.txt")
          .getContentAsString(StandardCharsets.UTF_8)
          .trim(),
        new ClassPathResource(
          "prompts/synthesis/prompt.txt"
        ).getContentAsString(StandardCharsets.UTF_8)
      );
    } catch (Exception e) {
      throw new IllegalStateException("Prompt resource missing", e);
    }
  }
}
