package com.signalframe.jobs.domain;

import com.signalframe.contract.*;
import java.util.*;

public interface JobRepository {
  AnalysisJob create(UUID newsId, String correlationId);
  Optional<AnalysisJob> find(UUID id);
  boolean claim(UUID id);
  void progress(UUID id, JobStatus status, String step, String message);
  void fail(UUID id, String message);
  void recoverInterrupted();
}
