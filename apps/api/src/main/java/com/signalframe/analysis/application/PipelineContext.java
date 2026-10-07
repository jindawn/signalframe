package com.signalframe.analysis.application;

import com.signalframe.contract.*;
import java.util.*;

public class PipelineContext {

  public final UUID jobId;
  public final String correlationId;
  public final NewsItem news;
  public DomainType domain = DomainType.OTHER;
  public NewsValueScore score;
  public List<Fact> facts = List.of();
  public AnalysisResult result;

  public PipelineContext(UUID id, String correlationId, NewsItem news) {
    this.jobId = id;
    this.correlationId = correlationId;
    this.news = news;
  }
}
