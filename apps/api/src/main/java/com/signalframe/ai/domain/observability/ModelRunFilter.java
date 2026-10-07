package com.signalframe.ai.domain.observability;

import com.signalframe.contract.ModelPurpose;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only selection over the model run audit table. Every field is optional;
 * {@link #all()} selects the whole retained audit history.
 */
public record ModelRunFilter(
  Instant from,
  Instant to,
  UUID jobId,
  String provider,
  String model,
  ModelPurpose purpose
) {

  public static ModelRunFilter all() {
    return new ModelRunFilter(null, null, null, null, null, null);
  }

  public static ModelRunFilter job(UUID jobId) {
    return new ModelRunFilter(null, null, jobId, null, null, null);
  }

  public static ModelRunFilter since(Instant from) {
    return new ModelRunFilter(from, null, null, null, null, null);
  }

  public ModelRunFilter withProviderModel(String provider, String model) {
    return new ModelRunFilter(
      from,
      to,
      jobId,
      provider,
      model,
      purpose
    );
  }

  public ModelRunFilter withPurpose(ModelPurpose purpose) {
    return new ModelRunFilter(
      from,
      to,
      jobId,
      provider,
      model,
      purpose
    );
  }
}
