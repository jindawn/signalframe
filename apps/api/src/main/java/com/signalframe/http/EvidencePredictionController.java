package com.signalframe.http;

import com.signalframe.contract.DuePredictions;
import com.signalframe.contract.Evidence;
import com.signalframe.contract.EvidenceCreateRequest;
import com.signalframe.contract.Prediction;
import com.signalframe.contract.PredictionCreateRequest;
import com.signalframe.contract.PredictionVerificationCommand;
import com.signalframe.contract.PredictionVerificationResult;
import com.signalframe.research.application.evidence.EvidencePredictionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * TASK-07's four allocated routes (Wave 2B contract freeze §2).
 *
 * <p>The controller is a thin adapter: it decodes, delegates and maps the outcome to
 * a status. Every rule lives in {@link EvidencePredictionService} and the domain, so
 * the behaviour is reachable and testable without HTTP.
 *
 * <p>The existing {@code ResearchController} read routes are untouched and live in
 * their own file, as the freeze requires.
 */
@RestController
@RequestMapping("/api/v1")
public class EvidencePredictionController {

  private final EvidencePredictionService service;

  public EvidencePredictionController(EvidencePredictionService service) {
    this.service = service;
  }

  /** Records sourced evidence against a hypothesis. */
  @PostMapping("/hypotheses/{id}/evidence")
  ResponseEntity<Evidence> addEvidence(
    @PathVariable UUID id,
    @Valid @RequestBody EvidenceCreateRequest request
  ) {
    return ResponseEntity.status(201).body(service.addEvidence(id, request));
  }

  /** Creates a dated, checkable prediction. It is always created {@code OPEN}. */
  @PostMapping("/hypotheses/{id}/predictions")
  ResponseEntity<Prediction> createPrediction(
    @PathVariable UUID id,
    @Valid @RequestBody PredictionCreateRequest request
  ) {
    return ResponseEntity.status(201).body(
      service.createPrediction(id, request)
    );
  }

  /**
   * Predictions still {@code OPEN} whose deadline has passed.
   *
   * <p>A literal path segment, so it cannot collide with a future
   * {@code /predictions/{id}} (freeze §2).
   */
  @GetMapping("/predictions/due")
  DuePredictions duePredictions() {
    return service.duePredictions();
  }

  /** Records a verification outcome against reality. Idempotent on {@code operationId}. */
  @PostMapping("/predictions/{id}/verification")
  PredictionVerificationResult verifyPrediction(
    @PathVariable UUID id,
    @Valid @RequestBody PredictionVerificationCommand command
  ) {
    return service.verifyPrediction(id, command);
  }
}
