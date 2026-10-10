package com.signalframe.analysis.application.steps;

import com.signalframe.contract.DomainType;
import com.signalframe.contract.JobStatus;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Pipeline step `ClassifyDomain` (protocol STG mapping: context only).
 *
 * <p>Deterministic and model-free: it sets {@code DomainType} so the strategy
 * layer can recommend variables and verification metrics. It produces no
 * epistemic artifact, and it assigns no confidence (protocol DS-01: strategies
 * and domain context never move the protocol's semantics). The keyword rule is
 * the foundation placeholder until TASK-05 owns domain dictionaries.
 */
@Component
public class DomainClassificationStage implements ProtocolStage {

  static final String NAME = "DomainClassification";
  static final String STEP = "ClassifyDomain";

  @Override
  public PipelineState execute(PipelineState in) {
    return in
      .withDomain(classify(in.news().source().text(), in.news().title()))
      .executed(NAME, null);
  }

  /**
   * Pure, deterministic keyword rule. It is shared with the draft stage so that
   * the prompt guidance and the recorded {@code DomainType} always agree.
   */
  static DomainType classify(String sourceText, String title) {
    String text = sourceText == null ? "" : sourceText.toLowerCase(Locale.ROOT);
    String heading = title == null ? "" : title.toLowerCase(Locale.ROOT);
    return text.contains("ai") ||
      text.contains("人工智能") ||
      heading.contains("人工智能")
      ? DomainType.AI
      : DomainType.OTHER;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public JobStatus status() {
    return JobStatus.ANALYZING;
  }

  @Override
  public String durableStep() {
    return STEP;
  }
}
