package com.signalframe.research.application.hypotheses;

import com.signalframe.contract.HypothesisTimeline;
import com.signalframe.research.domain.hypotheses.HypothesisTransitionRepository;
import com.signalframe.research.domain.hypotheses.TransitionErrors;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the append-only hypothesis timeline.
 *
 * <p>The timeline is the audit trail of every applied transition: status changes,
 * confidence changes and the reason each one carried. It is never rewritten —
 * {@code hypothesis_events} is append-only, and the current status and confidence
 * on {@link HypothesisTimeline} are the hypothesis row's present values, so a
 * client can always see both where the hypothesis is and how it got there.
 *
 * <p>The {@code version} on the result is the optimistic-lock token a caller must
 * send back as {@code expectedVersion}. Exposing it here is what makes the lock
 * usable at all: without a readable version there is nothing for a client to
 * send.
 */
@Service
public class HypothesisTimelineService {

  private final HypothesisTransitionRepository repository;

  public HypothesisTimelineService(HypothesisTransitionRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public HypothesisTimeline timeline(UUID hypothesisId) {
    var stored = repository
      .findById(hypothesisId)
      .orElseThrow(() -> TransitionErrors.notFound("hypothesis"));
    return new HypothesisTimeline(
      stored.id(),
      stored.version(),
      stored.payload().status(),
      stored.confidence(),
      repository.timeline(hypothesisId)
    );
  }
}
