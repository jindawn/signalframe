package com.signalframe.research.domain.hypotheses;

import com.signalframe.contract.Hypothesis;
import java.util.UUID;

/**
 * A hypothesis row as a transition sees it: the current payload, the
 * confidence column and the optimistic-concurrency version.
 *
 * <p>The score is read from the {@code confidence} column rather than from the
 * payload. Both are written together by every transition, but reading the column
 * means the previous score a transition reasons about is the one the relational
 * constraint guards ({@code CHECK (confidence BETWEEN 0 AND 100)}), and a stale
 * hand-edited payload cannot silently change it.
 */
public record StoredHypothesis(
  UUID id,
  Hypothesis payload,
  int confidence,
  long version
) {}
