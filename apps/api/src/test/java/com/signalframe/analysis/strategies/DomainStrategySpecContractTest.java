package com.signalframe.analysis.strategies;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.analysis.application.strategies.DomainStrategySpec;
import com.signalframe.analysis.application.strategies.MechanismTemplate;
import com.signalframe.analysis.application.strategies.VariableTemplate;
import com.signalframe.analysis.application.strategies.VerificationMetricTemplate;
import com.signalframe.analysis.domain.DomainAnalysisStrategy;
import com.signalframe.contract.DomainType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * DS-06, DS-08, DS-09, DS-11: the specs are pure immutable recommendations and can never carry a
 * claim, a figure, a provider name, a direction or a confidence value.
 */
class DomainStrategySpecContractTest {

  private static final List<DomainAnalysisStrategy> STRATEGIES =
    StrategyFixtures.strategies();

  private static final Pattern DIGIT = Pattern.compile(".*[0-9].*");

  private static final Pattern SUPPORT_ASSERTION = Pattern.compile(
    "\\b(SUPPORTED|CONFIRMED)\\b"
  );

  private static final Set<String> PROVIDER_OR_MODEL_NAMES = Set.of(
    "openai",
    "anthropic",
    "claude",
    "gemini",
    "gpt",
    "llama",
    "mistral",
    "deepseek",
    "grok",
    "copilot",
    "bedrock"
  );

  /** Field names that would let a template state a conclusion instead of a recommendation. */
  private static final Set<String> CLAIM_BEARING_FIELDS = Set.of(
    "direction",
    "magnitude",
    "confidence",
    "confidencescore",
    "probability",
    "score",
    "status",
    "conclusion",
    "forecast",
    "prediction",
    "supported",
    "truth"
  );

  @Test
  void everyMechanismTemplateStartsSpeculative() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      for (MechanismTemplate template : strategy.spec().mechanisms())
        assertEquals(
          MechanismTemplate.SPECULATIVE,
          template.startingSupportLevel(),
          "a strategy may never emit a supported mechanism (DS-08)"
        );
    }
  }

  @Test
  void aStrategyCannotConstructAMechanismAboveSpeculative() {
    assertThrows(IllegalArgumentException.class, () ->
      new MechanismTemplate("a", "b", "pattern", "evidence", "SUPPORTED")
    );
    assertThrows(IllegalArgumentException.class, () ->
      new MechanismTemplate("a", "b", "pattern", "evidence", "PLAUSIBLE")
    );
    assertThrows(IllegalArgumentException.class, () ->
      new MechanismTemplate("a", "b", "pattern", "evidence", null)
    );
  }

  @Test
  void noTemplateCarriesAClaimBearingField() {
    for (Class<?> type : List.of(
      VariableTemplate.class,
      MechanismTemplate.class,
      VerificationMetricTemplate.class,
      DomainStrategySpec.class
    )) {
      for (var component : type.getRecordComponents())
        assertFalse(
          CLAIM_BEARING_FIELDS.contains(
            component.getName().toLowerCase(Locale.ROOT)
          ),
          type.getSimpleName() +
            "." +
            component.getName() +
            " would let a strategy state a conclusion"
        );
    }
  }

  @Test
  void templatesContainNoFiguresOrProviderNames() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      for (String text : specText(strategy.spec())) {
        String lower = text.toLowerCase(Locale.ROOT);
        assertFalse(
          DIGIT.matcher(text).matches(),
          "a template must not carry an invented figure: " + text
        );
        assertFalse(
          text.contains("%") ||
            text.contains("$") ||
            text.contains("€") ||
            text.contains("¥"),
          "a template must not carry an invented figure: " + text
        );
        assertFalse(
          lower.contains("confidence") || lower.contains("probability"),
          "a strategy must not carry confidence language: " + text
        );
        assertFalse(
          SUPPORT_ASSERTION.matcher(text).find(),
          "a strategy must not assert support or confirmation: " + text
        );
        for (String name : PROVIDER_OR_MODEL_NAMES)
          assertFalse(
            lower.contains(name),
            "a strategy must not name a provider or model (" +
              name +
              "): " +
              text
          );
      }
    }
  }

  @Test
  void variableTemplatesAlwaysProvideADirectionRule() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      for (VariableTemplate template : strategy.spec().variables()) {
        assertFalse(template.directionRule().isBlank());
        assertFalse(template.name().isBlank());
        assertFalse(template.tracks().isBlank());
        assertFalse(template.whyItMatters().isBlank());
      }
    }
  }

  @Test
  void aBlankTemplateFieldIsRejected() {
    assertThrows(IllegalArgumentException.class, () ->
      new VariableTemplate("name", "  ", "why", "rule")
    );
    assertThrows(IllegalArgumentException.class, () ->
      new MechanismTemplate("from", "to", "", "evidence", "SPECULATIVE")
    );
    assertThrows(IllegalArgumentException.class, () ->
      new VerificationMetricTemplate("what", "where", "freq", "same", "same")
    );
  }

  @Test
  void strategySpecificityAgreesWithItsSpec() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      assertNotNull(strategy.spec().domain());
      assertTrue(strategy.spec().specificity() >= 0);
      assertEquals(
        strategy.specificity(),
        strategy.spec().specificity(),
        strategy.getClass().getName()
      );
    }
  }

  @Test
  void otherIsModelledByTheFallbackSpec() {
    for (DomainAnalysisStrategy strategy : STRATEGIES) {
      if (strategy.spec().specificity() != 0) continue;
      assertEquals(DomainType.OTHER, strategy.spec().domain());
    }
  }

  private static List<String> specText(DomainStrategySpec spec) {
    List<String> text = new ArrayList<>();
    for (VariableTemplate variable : spec.variables()) {
      text.add(variable.name());
      text.add(variable.tracks());
      text.add(variable.whyItMatters());
      text.add(variable.directionRule());
    }
    for (MechanismTemplate mechanism : spec.mechanisms()) {
      text.add(mechanism.fromConcept());
      text.add(mechanism.toConcept());
      text.add(mechanism.explanationPattern());
      text.add(mechanism.requiredEvidence());
    }
    for (VerificationMetricTemplate metric : spec.metrics()) {
      text.add(metric.whatToCheck());
      text.add(metric.whereToCheck());
      text.add(metric.frequency());
      text.add(metric.supportingResult());
      text.add(metric.contradictingResult());
    }
    text.addAll(spec.epistemicHazards());
    text.add(spec.guidanceText());
    return text;
  }
}
