package com.signalframe.analysis.application.steps;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Selects how the protocol stages obtain their model-backed artifacts.
 *
 * <p>The protocol is call-topology agnostic: "one synthesis call and fourteen
 * staged calls are both conformant, as long as the artifacts and the validation
 * gates hold" (ANALYSIS_PROTOCOL_V0_1 §1.5). Two conformant topologies are
 * implemented, and the choice is deployment configuration, never a provider or
 * model name:
 *
 * <ul>
 *   <li>{@link Mode#DRAFT} (default) — one schema-validated, audited provider call
 *       produces a draft snapshot through the existing runtime contract; every
 *       protocol stage then extracts, validates, ref-links and demotes its own
 *       typed artifact from that draft. This keeps the foundation's operational
 *       contract: exactly one audited synthesis run per analysis, which the
 *       existing live-provider audit test asserts and which Wave 2A recommends
 *       while per-stage model purposes stay integrator-owned.
 *   <li>{@link Mode#STAGED} — every model-backed stage issues its own typed call
 *       with its own versioned prompt, its own purpose and its own ModelRun audit
 *       rows, with bounded transport retry and one repair round per stage.
 * </ul>
 *
 * <p>Both modes run the same stage list, the same Gate B/C/D/E validation, the
 * same deterministic rubric and the same projection; only the artifact source
 * differs.
 */
@Component
public class AnalysisPipelineMode {

  public enum Mode {
    DRAFT,
    STAGED,
  }

  public static final String PROPERTY = "analysis.pipeline.mode";

  private final Mode mode;

  @Autowired
  public AnalysisPipelineMode(
    @Value("${analysis.pipeline.mode:draft}") String raw
  ) {
    this.mode = parse(raw);
  }

  /** Test/seam constructor. */
  public AnalysisPipelineMode(Mode mode) {
    this.mode = mode == null ? Mode.DRAFT : mode;
  }

  public Mode mode() {
    return mode;
  }

  public boolean staged() {
    return mode == Mode.STAGED;
  }

  private static Mode parse(String raw) {
    if (raw == null || raw.isBlank()) return Mode.DRAFT;
    try {
      return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalStateException(
        PROPERTY + " must be 'draft' or 'staged', got: " + raw
      );
    }
  }
}
