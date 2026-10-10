package com.signalframe.analysis.application.strategies;

import java.util.List;

/**
 * Deterministic rendering of a {@link DomainStrategySpec} into the bounded prompt guidance string
 * (DS-07, {@value #MAX_GUIDANCE_LENGTH} characters).
 *
 * <p>Section order, line format and the character budget are fixed, so identical specs always render
 * identical text. Domain hazards are never dropped: the protocol appends them to guidance for the
 * reasoning stages (DS-10). When a section's list does not fit its share of the budget, the
 * remaining items are summarised as an omission count — the full lists stay available through
 * {@code spec()} to the stages that consume them (STG-04, STG-05, STG-13, STG-15).
 */
public final class DomainStrategySpecRenderer {

  public static final int MAX_GUIDANCE_LENGTH = 2000;

  private static final String CAVEAT =
    "Recommendations only: no fact, no direction and no confidence is asserted here.";

  private static final String VARIABLE_TITLE =
    "Variables to check (recommendations; UNKNOWN over an invented value):";

  private static final String MECHANISM_TITLE =
    "Candidate mechanisms (each starts SPECULATIVE; only the analysis stage may raise the level, and only with fact refs):";

  private static final String METRIC_TITLE =
    "Verification checks (observable only; what to look at and where):";

  private static final String HAZARD_TITLE =
    "Domain hazards (advisory; never a confidence adjustment):";

  private static final String LINE = "\n- ";

  private DomainStrategySpecRenderer() {}

  public static String render(DomainStrategySpec spec) {
    String header =
      "Domain " +
      spec.domain() +
      " strategy (specificity " +
      spec.specificity() +
      "). " +
      CAVEAT;
    String guidance = "Domain guidance: " + spec.guidanceText();
    String hazards = title(HAZARD_TITLE) + lines(spec.epistemicHazards());

    List<String> variableLines = spec
      .variables()
      .stream()
      .map(DomainStrategySpecRenderer::variable)
      .toList();
    List<String> mechanismLines = spec
      .mechanisms()
      .stream()
      .map(DomainStrategySpecRenderer::mechanism)
      .toList();
    List<String> metricLines = spec
      .metrics()
      .stream()
      .map(DomainStrategySpecRenderer::metric)
      .toList();

    int reserved =
      header.length() +
      guidance.length() +
      hazards.length() +
      VARIABLE_TITLE.length() +
      MECHANISM_TITLE.length() +
      METRIC_TITLE.length() +
      8; // newlines joining the header, guidance and the four sections
    int detailBudget = Math.max(0, MAX_GUIDANCE_LENGTH - reserved);
    int items =
      variableLines.size() + mechanismLines.size() + metricLines.size();
    int variableQuota = (detailBudget * variableLines.size()) / items;
    int mechanismQuota = (detailBudget * mechanismLines.size()) / items;
    int metricQuota = detailBudget - variableQuota - mechanismQuota;

    String text =
      header +
      "\n" +
      guidance +
      "\n" +
      title(VARIABLE_TITLE) +
      bounded(variableLines, variableQuota, "variable") +
      "\n" +
      title(MECHANISM_TITLE) +
      bounded(mechanismLines, mechanismQuota, "mechanism") +
      "\n" +
      title(METRIC_TITLE) +
      bounded(metricLines, metricQuota, "check") +
      "\n" +
      hazards;
    if (text.length() <= MAX_GUIDANCE_LENGTH) return text;
    int cut = text.lastIndexOf('\n', MAX_GUIDANCE_LENGTH - 1);
    return text.substring(0, cut > 0 ? cut : MAX_GUIDANCE_LENGTH);
  }

  private static String title(String text) {
    return "\n" + text;
  }

  private static String lines(List<String> values) {
    StringBuilder out = new StringBuilder();
    for (String value : values) out.append(LINE).append(value);
    return out.toString();
  }

  /** Appends whole lines while the share allows it, then counts what was left out (DS-09). */
  private static String bounded(List<String> entries, int quota, String label) {
    StringBuilder out = new StringBuilder();
    int used = 0;
    int included = 0;
    for (String entry : entries) {
      String line = LINE + entry;
      if (used + line.length() > quota) break;
      out.append(line);
      used += line.length();
      included++;
    }
    int omitted = entries.size() - included;
    if (omitted > 0) {
      String note =
        LINE +
        "(+ " +
        omitted +
        " more " +
        label +
        (omitted == 1 ? "" : "s") +
        "; the strategy spec holds the full list)";
      if (used + note.length() <= quota) out.append(note);
    }
    return out.toString();
  }

  private static String variable(VariableTemplate template) {
    return template.name() + " | direction: " + template.directionRule();
  }

  private static String mechanism(MechanismTemplate template) {
    return (
      template.fromConcept() +
      " -> " +
      template.toConcept() +
      " | evidence needed: " +
      template.requiredEvidence()
    );
  }

  private static String metric(VerificationMetricTemplate template) {
    return template.whatToCheck() + " | where: " + template.whereToCheck();
  }
}
