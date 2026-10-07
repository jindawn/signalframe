package com.signalframe.analysis.application;

import com.signalframe.ai.application.ModelAnalysisService;
import com.signalframe.analysis.application.steps.FoundationStep;
import com.signalframe.analysis.domain.*;
import com.signalframe.contract.*;
import com.signalframe.jobs.domain.JobRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class AnalysisPipeline {

  private final List<AnalysisStep<PipelineContext, PipelineContext>> steps;
  private final JobRepository jobs;

  public AnalysisPipeline(
    JobRepository jobs,
    AnalysisRepository analyses,
    NewsValueScorer scorer,
    ModelAnalysisService model,
    List<DomainAnalysisStrategy> strategies
  ) {
    this.jobs = jobs;
    var list = new ArrayList<AnalysisStep<PipelineContext, PipelineContext>>();
    list.add(
      step("NormalizeInput", JobStatus.NORMALIZING, c -> {
        if (
          c.news.source().text().isBlank()
        ) throw new IllegalArgumentException("Text required");
      })
    );
    list.add(
      step("ExtractFacts", JobStatus.EXTRACTING_FACTS, c -> {
        String q = c.news
          .source()
          .text()
          .substring(0, Math.min(200, c.news.source().text().length()));
        c.facts = List.of(
          new Fact(
            UUID.randomUUID(),
            ClaimType.FACT,
            q,
            "Reported source claim; not independently verified.",
            40,
            List.of(new SourceRef(c.news.source().id(), q, 0, q.length()))
          )
        );
      })
    );
    list.add(
      step("ClassifyDomain", JobStatus.ANALYZING, c -> {
        String t = c.news.source().text().toLowerCase(Locale.ROOT);
        c.domain =
          t.contains("ai") || t.contains("人工智能")
            ? DomainType.AI
            : DomainType.OTHER;
      })
    );
    list.add(
      step(
        "ScoreNews",
        JobStatus.ANALYZING,
        c -> c.score = scorer.score(c.news)
      )
    );
    for (String name : List.of(
      "ExtractVariables",
      "AnalyzeMechanism",
      "AnalyzeStakeholders"
    ))
      list.add(step(name, JobStatus.ANALYZING, c -> {}));
    for (String name : List.of(
      "GenerateHypotheses",
      "GenerateCounterArguments"
    ))
      list.add(step(name, JobStatus.GENERATING_HYPOTHESES, c -> {}));
    for (String name : List.of(
      "GenerateFalsificationConditions",
      "GenerateVerificationPlan",
      "GenerateCorroboratingSignals"
    ))
      list.add(step(name, JobStatus.VERIFYING, c -> {}));
    list.add(
      step("SynthesizeAnalysis", JobStatus.SYNTHESIZING, c -> {
        var strategy = strategies
          .stream()
          .filter(s -> s.supports(c.domain))
          .sorted(
            Comparator.comparing(s ->
              s.getClass().getSimpleName().startsWith("Default")
            )
          )
          .findFirst()
          .orElseThrow();
        c.result = model.synthesize(
          c.jobId,
          c.correlationId,
          c.news,
          strategy.guidance()
        );
      })
    );
    list.add(
      step("Persist", JobStatus.SYNTHESIZING, c ->
        analyses.complete(
          new Analysis(
            UUID.randomUUID(),
            c.news.id(),
            c.jobId,
            c.domain,
            c.score,
            c.result,
            Instant.now()
          )
        )
      )
    );
    steps = List.copyOf(list);
  }

  private FoundationStep step(
    String name,
    JobStatus status,
    Consumer<PipelineContext> action
  ) {
    return new FoundationStep(name, status, action);
  }

  public List<String> stepNames() {
    return steps.stream().map(AnalysisStep::name).toList();
  }

  public void run(PipelineContext context) {
    for (var step : steps) {
      jobs.progress(context.jobId, step.status(), step.name(), step.name());
      step.execute(context);
    }
  }
}
