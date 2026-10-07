package com.signalframe.research.domain;

import java.util.*;

public interface SemanticRetriever {
  record Match(UUID sourceId, String excerpt, double relevance) {}

  List<Match> retrieve(String query, int limit);
}
