package com.signalframe.research.domain.hypotheses;

import java.util.UUID;

/**
 * An evidence row as the hypothesis scope reads it.
 *
 * <p>Only the columns a deterministic scoring decision depends on are carried:
 * which hypothesis the item bears on, which source it came from — which is what
 * makes corroboration independent (CONFIDENCE_MODEL §5 D3) — and the stance. The
 * JSONB payload belongs to TASK-07's evidence record and is deliberately not read
 * here, so a hypothesis transition can never depend on the shape of another
 * task's payload.
 */
public record StoredEvidence(
  UUID id,
  UUID hypothesisId,
  UUID sourceId,
  EvidenceStance stance
) {}
