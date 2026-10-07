package com.signalframe.analysis.application;

import com.signalframe.analysis.domain.NewsValueScorer;
import com.signalframe.contract.*;
import org.springframework.stereotype.Component;

@Component
public class DefaultNewsValueScorer implements NewsValueScorer {

  public NewsValueScore score(NewsItem item) {
    int verifiability = item.source().url() == null ? 30 : 60;
    return new NewsValueScore(
      NewsGrade.B,
      50,
      50,
      50,
      50,
      verifiability,
      "Foundation baseline, not a learned score. A/S routing reserved for later implementation."
    );
  }
}
