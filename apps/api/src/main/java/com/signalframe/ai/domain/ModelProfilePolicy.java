package com.signalframe.ai.domain;

import com.signalframe.contract.ModelProfile;

/** Provider capability and activation policy implemented only in infrastructure. */
public interface ModelProfilePolicy {
  void validate(ModelProfile profile);
  String effectiveMode(ModelProfile profile);
}
