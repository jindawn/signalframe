package com.signalframe.analysis.application.steps;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the versioned stage prompts reserved under {@code resources/prompts}.
 *
 * <p>Every model-backed stage has its own directory with {@code version.txt} and
 * {@code prompt.txt}. The version is what a ModelRun records and what the
 * confidence reason cites (protocol PR-06/PR-15). Prompts are never inlined in
 * stage code, so a prompt change is a versioned resource change.
 */
@Component
public class PromptLibrary {

  public record VersionedPrompt(String version, String text) {}

  private final ConcurrentHashMap<String, VersionedPrompt> cache =
    new ConcurrentHashMap<>();

  public VersionedPrompt load(String directory) {
    return cache.computeIfAbsent(directory, dir -> {
      try {
        String version = new ClassPathResource(
          "prompts/" + dir + "/version.txt"
        )
          .getContentAsString(StandardCharsets.UTF_8)
          .trim();
        String text = new ClassPathResource(
          "prompts/" + dir + "/prompt.txt"
        ).getContentAsString(StandardCharsets.UTF_8);
        if (version.isBlank() || text.isBlank()) throw new IllegalStateException(
          "Prompt resource is empty"
        );
        return new VersionedPrompt(version, text);
      } catch (Exception e) {
        throw new IllegalStateException(
          "Prompt resource missing for stage prompt '" + dir + "'",
          e
        );
      }
    });
  }
}
