package com.signalframe.analysis.domain;

import com.signalframe.contract.*;

public interface NewsValueScorer {
  NewsValueScore score(NewsItem item);
}
