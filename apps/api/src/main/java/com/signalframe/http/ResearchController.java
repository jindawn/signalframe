package com.signalframe.http;

import com.signalframe.contract.*;
import com.signalframe.research.application.ResearchService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ResearchController {

  private final ResearchService research;

  public ResearchController(ResearchService research) {
    this.research = research;
  }

  @GetMapping("/hypotheses")
  List<Hypothesis> hypotheses() {
    return research.hypotheses();
  }

  @GetMapping("/hypotheses/{id}")
  HypothesisDetail hypothesis(@PathVariable UUID id) {
    return research.hypothesis(id);
  }

  @GetMapping("/topics")
  List<Topic> topics() {
    return research.topics();
  }

  @GetMapping("/topics/{id}")
  Topic topic(@PathVariable UUID id) {
    return research.topic(id);
  }
}
