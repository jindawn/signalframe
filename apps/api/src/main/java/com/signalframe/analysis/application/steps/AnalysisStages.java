package com.signalframe.analysis.application.steps;

import com.signalframe.contract.JobStatus;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * The fixed durable pipeline: 14 named steps and their internal protocol stages.
 *
 * <p>Status granularity is unchanged from ADR-004: the {@link JobStatus} sequence
 * and the 14 step names are exactly what they were, and no new status exists. The
 * protocol's finer stages (ANALYSIS_PROTOCOL_V0_1 §4) are grouped behind those
 * boundaries, so a step reports one durable progress event while its internal
 * stages stay independently testable.
 *
 * <p>Order is declared here, once, as an explicit list — never inferred from
 * component scanning order, class names or autowiring. Registration validates
 * completeness: an unregistered or duplicated protocol stage fails at startup.
 */
@Component
public class AnalysisStages {

  /** One durable step and the protocol stages that run inside it. */
  public record DurableStep(
    String name,
    JobStatus status,
    List<ProtocolStage> stages
  ) {
    public DurableStep {
      stages = List.copyOf(stages);
    }
  }

  private record StepDefinition(
    String name,
    JobStatus status,
    List<String> stages
  ) {}

  private static final List<StepDefinition> DEFINITION = List.of(
    new StepDefinition(
      "NormalizeInput",
      JobStatus.NORMALIZING,
      List.of("SourceAssessment")
    ),
    new StepDefinition(
      "ExtractFacts",
      JobStatus.EXTRACTING_FACTS,
      List.of("SnapshotDraft", "FactExtraction")
    ),
    new StepDefinition(
      "ClassifyDomain",
      JobStatus.ANALYZING,
      List.of("DomainClassification")
    ),
    new StepDefinition(
      "ScoreNews",
      JobStatus.ANALYZING,
      List.of("NewsScoring")
    ),
    new StepDefinition(
      "ExtractVariables",
      JobStatus.ANALYZING,
      List.of("VariableAnalysis")
    ),
    new StepDefinition(
      "AnalyzeMechanism",
      JobStatus.ANALYZING,
      List.of("MechanismAnalysis", "FirstOrderEffect", "SecondOrderEffect")
    ),
    new StepDefinition(
      "AnalyzeStakeholders",
      JobStatus.ANALYZING,
      List.of("StakeholderAnalysis")
    ),
    new StepDefinition(
      "GenerateHypotheses",
      JobStatus.GENERATING_HYPOTHESES,
      List.of("HypothesisGeneration")
    ),
    new StepDefinition(
      "GenerateCounterArguments",
      JobStatus.GENERATING_HYPOTHESES,
      List.of("AlternativeExplanation", "CounterArgument")
    ),
    new StepDefinition(
      "GenerateFalsificationConditions",
      JobStatus.VERIFYING,
      List.of("FalsificationCondition")
    ),
    new StepDefinition(
      "GenerateCorroboratingSignals",
      JobStatus.VERIFYING,
      List.of("CorroboratingSignal")
    ),
    new StepDefinition(
      "GenerateVerificationPlan",
      JobStatus.VERIFYING,
      List.of("PredictionGeneration", "VerificationPlan")
    ),
    new StepDefinition(
      "SynthesizeAnalysis",
      JobStatus.SYNTHESIZING,
      List.of(
        "EpistemicGate",
        "ConfidenceAssessment",
        "Synthesis",
        "Projection"
      )
    ),
    new StepDefinition(
      "Persist",
      JobStatus.SYNTHESIZING,
      List.of("Persist")
    )
  );

  private final List<DurableStep> steps;

  public AnalysisStages(List<ProtocolStage> protocolStages) {
    var byName = new LinkedHashMap<String, ProtocolStage>();
    for (var stage : protocolStages) {
      var duplicate = byName.put(stage.name(), stage);
      if (duplicate != null) throw new IllegalStateException(
        "Duplicate protocol stage name: " + stage.name()
      );
    }
    var built = new ArrayList<DurableStep>();
    for (var definition : DEFINITION) {
      var group = new ArrayList<ProtocolStage>();
      for (var stageName : definition.stages()) {
        var stage = byName.remove(stageName);
        if (stage == null) throw new IllegalStateException(
          "Protocol stage is not registered: " + stageName
        );
        group.add(stage);
      }
      built.add(
        new DurableStep(definition.name(), definition.status(), group)
      );
    }
    if (!byName.isEmpty()) throw new IllegalStateException(
      "Protocol stages are not part of the fixed pipeline: " + byName.keySet()
    );
    this.steps = List.copyOf(built);
  }

  public List<DurableStep> steps() {
    return steps;
  }
}
