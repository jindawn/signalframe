package com.signalframe.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * TASK-10 acceptance: "fixtures have no secrets".
 *
 * <p>This is a guard, not a scan for known leaks: the patterns describe the
 * <em>shape</em> of real credentials, and the strings are assembled from
 * fragments so that the guard's own source cannot satisfy its own patterns. It
 * walks the committed test trees only — production configuration reads its
 * credentials from the environment and is checked by the runtime assertions in
 * the flow suites (no credential reaches the audit trail).
 */
class FixtureSecretScanTest {

  private static final List<Pattern> CREDENTIAL_SHAPES = List.of(
    // OpenAI-style key.
    Pattern.compile("\\b" + "s" + "k-[A-Za-z0-9]{20,}\\b"),
    // AWS access key id.
    Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
    // PEM private key block.
    Pattern.compile("BEGIN [A-Z ]*" + "PRIVATE KEY"),
    // Bearer token.
    Pattern.compile("Bearer\\s+[A-Za-z0-9\\-._~+/]{20,}"),
    // JWT.
    Pattern.compile(
      "\\beyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}"
    ),
    // Literal credential assignment.
    Pattern.compile(
      "(?i)(api[_-]?key|secret|token)\\s*[:=]\\s*\"[A-Za-z0-9_\\-]{16,}\""
    )
  );

  private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
    "target",
    "node_modules",
    ".next",
    "test-results",
    "playwright-report"
  );

  @Test
  void fixturesAndTestSourcesCarryNoCredentialShapedLiterals() throws Exception {
    var roots = List.of(Path.of("src/test"), Path.of("../web/tests"));
    var scanned = new ArrayList<String>();

    for (var root : roots) {
      if (!Files.isDirectory(root)) continue;
      try (var files = Files.walk(root)) {
        for (var file : files.filter(Files::isRegularFile).toList()) {
          if (isSkipped(file)) continue;
          var text = Files.readString(file);
          for (var pattern : CREDENTIAL_SHAPES) assertFalse(
            pattern.matcher(text).find(),
            file + " contains a credential-shaped literal (" + pattern + ")"
          );
          scanned.add(file.toString());
        }
      }
    }

    assertTrue(
      scanned.stream().anyMatch(path -> path.endsWith(".java")),
      "the scan must cover the backend test tree"
    );
    assertTrue(
      scanned.stream().anyMatch(path -> path.endsWith(".ts")),
      "the scan must cover the browser test tree"
    );
  }

  private static boolean isSkipped(Path file) {
    for (var part : file) if (SKIPPED_DIRECTORIES.contains(part.toString())) {
      return true;
    }
    return false;
  }
}
