package com.signalframe.research.application;

import com.signalframe.contract.*;
import com.signalframe.research.domain.ResearchRepository;
import com.signalframe.shared.ApplicationException;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ResearchService {

  private final ResearchRepository repository;

  public ResearchService(ResearchRepository r) {
    repository = r;
  }

  public List<Hypothesis> hypotheses() {
    return repository.hypotheses();
  }

  public HypothesisDetail hypothesis(UUID id) {
    return repository.hypothesis(id).orElseThrow(ApplicationException::missing);
  }

  public List<Topic> topics() {
    return repository.topics();
  }

  public Topic topic(UUID id) {
    return repository.topic(id).orElseThrow(ApplicationException::missing);
  }
}
