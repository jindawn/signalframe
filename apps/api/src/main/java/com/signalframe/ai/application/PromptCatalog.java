package com.signalframe.ai.application;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PromptCatalog {

  /**
   * One versioned synthesis prompt.
   *
   * <p>{@code text} carries the analysis instructions and is placed at the head of
   * the assembled prompt; {@code outputContract} carries the reply-shape rules and
   * is placed at the tail, after the schema and the source, so it is the text the
   * model reads immediately before answering (see {@link AnalysisPromptFrame}).
   * Both files live under {@code prompts/synthesis} and are covered by the same
   * {@code version.txt}, so a change to either is a prompt version change.
   */
  public record VersionedPrompt(
    String version,
    String text,
    String outputContract
  ) {}

  public VersionedPrompt synthesis() {
    return new VersionedPrompt(
      read("prompts/synthesis/version.txt").trim(),
      read("prompts/synthesis/prompt.txt"),
      read("prompts/synthesis/output-contract.txt")
    );
  }

  private static String read(String resource) {
    try {
      return new ClassPathResource(resource).getContentAsString(
        StandardCharsets.UTF_8
      );
    } catch (Exception e) {
      throw new IllegalStateException(
        "Prompt resource missing: " + resource,
        e
      );
    }
  }
}
