package com.signalframe.http;

import com.signalframe.contract.HypothesisTimeline;
import com.signalframe.contract.HypothesisTransitionCommand;
import com.signalframe.contract.HypothesisTransitionResult;
import com.signalframe.research.application.hypotheses.HypothesisTimelineService;
import com.signalframe.research.application.hypotheses.HypothesisTransitionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * The Wave 2B hypothesis command surface (freeze §2).
 *
 * <p>Two routes, both allocated to TASK-06 by the freeze and both additive under
 * the existing {@code /api/v1} root:
 *
 * <ul>
 *   <li>{@code POST /api/v1/hypotheses/{id}/transitions} — apply one transition and
 *       return the optimistic-lock movement, the status transition and the
 *       deterministic band. {@code 200} on success, {@code 400} for a structurally
 *       invalid body, {@code 404} for an unknown hypothesis or reference,
 *       {@code 409} for a stale version or an idempotency-key mismatch, and
 *       {@code 422} when the transition is well formed but not allowed.</li>
 *   <li>{@code GET /api/v1/hypotheses/{id}/timeline} — the append-only timeline plus
 *       the current {@code version} the next transition must cite.</li>
 * </ul>
 *
 * <p>{@code ResearchController}'s four read routes are untouched, and the request
 * and response bodies are the generated contract records, so HTTP, the port and
 * the persisted event keep describing one vocabulary.
 */
@RestController
@RequestMapping("/api/v1")
public class HypothesisCommandController {

  private final HypothesisTransitionService transitions;
  private final HypothesisTimelineService timelines;

  public HypothesisCommandController(
    HypothesisTransitionService transitions,
    HypothesisTimelineService timelines
  ) {
    this.transitions = transitions;
    this.timelines = timelines;
  }

  @PostMapping("/hypotheses/{id}/transitions")
  HypothesisTransitionResult transition(
    @PathVariable UUID id,
    @RequestBody @Valid HypothesisTransitionCommand command
  ) {
    return transitions.transition(id, command);
  }

  @GetMapping("/hypotheses/{id}/timeline")
  HypothesisTimeline timeline(@PathVariable UUID id) {
    return timelines.timeline(id);
  }
}
