package com.signalframe.ai.application;

import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Gate A, and the part of Gate B/C that the snapshot schema makes expressible.
 *
 * <p>Gate A is structural: the generated bean-validation constraints (ranges,
 * patterns, required fields) plus the verbatim source-span check. Gate B is
 * epistemic and covers the rules the schema can carry after SCH-01…SCH-13 —
 * FACT-only in {@code facts}, UNKNOWN never mixed into a claiming list (EP-06),
 * typed unknowns, and corroborating signals that stay expected observables rather
 * than facts. Gate C is provenance: every {@code SourceRef} resolves verbatim
 * against the input source, and every prediction names a hypothesis that exists
 * inside the same snapshot (PR-01/PR-09).
 *
 * <p>Gates D (rubric recomputation) and E (stage completeness) are enforced by the
 * pipeline stages, which own the rubric inputs; this validator runs on the serialised
 * snapshot and cannot reconstruct them.
 */
@Component
public class AnalysisResultValidator {

  private final JsonCodec json;
  private final Validator validator;

  public AnalysisResultValidator(JsonCodec json, Validator validator) {
    this.json = json;
    this.validator = validator;
  }

  public AnalysisResult parse(String raw, NewsItem news) {
    AnalysisResult r;
    try {
      r = json.read(raw, AnalysisResult.class);
    } catch (RuntimeException malformed) {
      // A payload that cannot even be deserialised (for example a status value
      // outside the contract vocabulary) is an invalid snapshot, reported through
      // the same exception type as every other validation failure.
      throw new IllegalArgumentException(
        "Invalid analysis schema: " + malformed.getMessage(),
        malformed
      );
    }
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
    var hypothesisIds = new HashSet<UUID>();
    for (var h : r.hypotheses()) {
      if (
        h.type() != ClaimType.HYPOTHESIS ||
        h.updatedAt().isBefore(h.createdAt())
      ) throw new IllegalArgumentException("Hypothesis type/time invalid");
      refs(h.sourceRefs(), news);
      hypothesisIds.add(h.id());
    }
    // EP-06: UNKNOWN is a first-class output, never a vague inference, so an
    // unknown item is typed UNKNOWN and a claiming list never contains one.
    for (var x : r.unknowns()) {
      if (x.type() != ClaimType.UNKNOWN) throw new IllegalArgumentException(
        "unknowns items must be typed UNKNOWN (EP-06)"
      );
      refs(x.sourceRefs(), news);
    }
    for (var list : claimingLists(r)) for (var x : list) {
      if (x.type() == ClaimType.FACT) throw new IllegalArgumentException(
        "FACT only allowed in facts"
      );
      if (x.type() == ClaimType.UNKNOWN) throw new IllegalArgumentException(
        "UNKNOWN only allowed in unknowns (EP-06)"
      );
      refs(x.sourceRefs(), news);
    }
    // `upcomingObservations` is a reading list (EPISTEMIC_TYPES §5.4): it may hold
    // an UNKNOWN to say what is still unread, but it may never assert a FACT.
    for (var x : r.upcomingObservations()) {
      if (x.type() == ClaimType.FACT) throw new IllegalArgumentException(
        "FACT only allowed in facts"
      );
      refs(x.sourceRefs(), news);
    }
    r.corroboratingSignals().forEach(x -> {
      if (
        x.type() == ClaimType.FACT || x.type() == ClaimType.UNKNOWN
      ) throw new IllegalArgumentException(
        "Corroborating signals are expected observables, not facts or unknowns (§5.2)"
      );
      refs(x.sourceRefs(), news);
    });
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
    // Gate C: a prediction must name a hypothesis that exists inside this
    // snapshot, and carries its own dated, checkable identity (SCH-07/PR-01).
    for (var p : r.predictions()) {
      if (!"PREDICTION".equals(p.type())) throw new IllegalArgumentException(
        "Predictions must carry the PREDICTION type"
      );
      if (p.expectedBy() == null) throw new IllegalArgumentException(
        "Prediction expectedBy is required"
      );
      if (!hypothesisIds.contains(p.hypothesisId())) throw new IllegalArgumentException(
        "Prediction hypothesisId must resolve inside the snapshot (PR-01)"
      );
    }
    return r;
  }

  /** Lists whose items make a claim about the facts and must be INFERENCE. */
  private static List<List<Statement>> claimingLists(AnalysisResult r) {
    return List.of(
      r.firstOrderEffects(),
      r.secondOrderEffects(),
      r.alternativeExplanations(),
      r.counterArguments(),
      r.falsificationConditions()
    );
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
