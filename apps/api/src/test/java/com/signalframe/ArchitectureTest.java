package com.signalframe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

  @Test
  void domainAndApplicationHaveNoProviderDependencies() throws Exception {
    try (var files = Files.walk(Path.of("src/main/java/com/signalframe"))) {
      for (var file : files
        .filter(p -> p.toString().endsWith(".java"))
        .toList()) {
        String path = file.toString(),
          text = Files.readString(file);
        if (path.contains("/domain/") || path.contains("/application/")) {
          assertFalse(text.contains("org.springframework.ai.openai"), path);
          assertFalse(text.contains("com.signalframe.infrastructure"), path);
          assertFalse(text.toLowerCase().contains("deepseek"), path);
          assertFalse(text.contains("\"openai-compatible\""), path);
          assertFalse(text.contains("\"mock\""), path);
        }
      }
    }
  }

  @Test
  void privateContentDestinationsAreRejected() {
    assertThrows(Exception.class, () ->
      com.signalframe.infrastructure.news.PublicWebContentExtractor.validate(
        java.net.URI.create("http://127.0.0.1")
      )
    );
    assertThrows(Exception.class, () ->
      com.signalframe.infrastructure.news.PublicWebContentExtractor.validate(
        java.net.URI.create("http://[::1]")
      )
    );
    assertThrows(Exception.class, () ->
      com.signalframe.infrastructure.news.PublicWebContentExtractor.validate(
        java.net.URI.create("file:///etc/passwd")
      )
    );
  }
}
