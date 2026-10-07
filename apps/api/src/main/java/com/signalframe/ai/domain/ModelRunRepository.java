package com.signalframe.ai.domain;

import com.signalframe.contract.ModelRun;
import java.util.*;

public interface ModelRunRepository {
  void save(ModelRun run);
  List<ModelRun> recent(UUID jobId);
}
