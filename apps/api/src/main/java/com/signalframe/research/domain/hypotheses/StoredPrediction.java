package com.signalframe.research.domain.hypotheses;

import java.time.Instant;
import java.util.UUID;

/**
 * A prediction row as the hypothesis scope reads it.
 *
 * <p>A prediction carries no confidence (EPISTEMIC_TYPES §2.4, CONFIDENCE_MODEL
 * §8): it carries a status, and that status is the verification record a
 * {@code CONFIRMED} hypothesis must cite (EPISTEMIC_TYPES §2.3). {@code verifiedAt}
 * is the timestamp the verification record set.
 */
public record StoredPrediction(
  UUID id,
  UUID hypothesisId,
  String status,
  Instant verifiedAt
) {}
