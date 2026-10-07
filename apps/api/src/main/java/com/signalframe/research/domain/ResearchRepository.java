package com.signalframe.research.domain;

import com.signalframe.contract.*;
import java.util.*;

public interface ResearchRepository {
  List<Hypothesis> hypotheses();
  Optional<HypothesisDetail> hypothesis(UUID id);
  List<Topic> topics();
  Optional<Topic> topic(UUID id);
}
