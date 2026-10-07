package com.signalframe.analysis.domain;

import com.signalframe.contract.JobStatus;

public interface AnalysisStep<I, O> {
  String name();
  JobStatus status();
  O execute(I input);
}
