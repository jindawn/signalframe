package com.signalframe.ai.application;

import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AnalysisResultValidator {

  private final JsonCodec json;
  private final Validator validator;

  public AnalysisResultValidator(JsonCodec json, Validator validator) {
    this.json = json;
    this.validator = validator;
  }

  public AnalysisResult parse(String raw, NewsItem news) {
    AnalysisResult r = json.read(raw, AnalysisResult.class);
    if (
      !validator.validate(r).isEmpty() ||
      r.confidenceAssessment().isProbability() ||
      r.modifiesExistingHypotheses()
    ) throw new IllegalArgumentException("Invalid analysis schema");
    if (r.facts().isEmpty()) throw new IllegalArgumentException(
      "Fact provenance is required"
    );
    for (var f : r.facts()) {
      if (
        f.type() != ClaimType.FACT || f.sourceRefs().isEmpty()
      ) throw new IllegalArgumentException("Fact type/provenance required");
      refs(f.sourceRefs(), news);
      if (
        f
          .sourceRefs()
          .stream()
          .noneMatch(ref -> ref.quote().equals(f.statement()))
      ) throw new IllegalArgumentException(
        "Fact statement must quote source verbatim"
      );
    }
    for (var h : r.hypotheses()) {
      if (
        h.type() != ClaimType.HYPOTHESIS ||
        h.updatedAt().isBefore(h.createdAt())
      ) throw new IllegalArgumentException("Hypothesis type/time invalid");
      refs(h.sourceRefs(), news);
    }
    r.variables().forEach(x -> {
      inference(x.type());
      refs(x.sourceRefs(), news);
    });
    r.mechanisms().forEach(x -> {
      inference(x.type());
      refs(x.sourceRefs(), news);
    });
    r.stakeholders().forEach(x -> {
      inference(x.type());
      refs(x.sourceRefs(), news);
    });
    r.verificationIndicators().forEach(x -> {
      inference(x.type());
      refs(x.sourceRefs(), news);
    });
    for (var list : List.of(
      r.firstOrderEffects(),
      r.secondOrderEffects(),
      r.alternativeExplanations(),
      r.counterArguments(),
      r.falsificationConditions(),
      r.corroboratingSignals(),
      r.unknowns(),
      r.upcomingObservations()
    ))
      for (var x : list) {
        if (x.type() == ClaimType.FACT) throw new IllegalArgumentException(
          "FACT only allowed in facts"
        );
        refs(x.sourceRefs(), news);
      }
    return r;
  }

  private void inference(ClaimType type) {
    if (type != ClaimType.INFERENCE) throw new IllegalArgumentException(
      "Interpretations must be INFERENCE"
    );
  }

  private void refs(List<SourceRef> refs, NewsItem news) {
    for (var r : refs) {
      if (
        !r.sourceId().equals(news.source().id()) ||
        r.startOffset() < 0 ||
        r.endOffset() > news.source().text().length() ||
        r.endOffset() <= r.startOffset() ||
        !news
          .source()
          .text()
          .substring(r.startOffset(), r.endOffset())
          .equals(r.quote())
      ) throw new IllegalArgumentException("Invalid source span");
    }
  }
}
