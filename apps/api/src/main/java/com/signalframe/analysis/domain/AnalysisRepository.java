package com.signalframe.analysis.domain;

import com.signalframe.contract.*;
import java.util.*;

public interface AnalysisRepository {
  void complete(Analysis analysis);
  Optional<Analysis> find(UUID id);
  java.util.List<Analysis> forNews(UUID newsId);
}
