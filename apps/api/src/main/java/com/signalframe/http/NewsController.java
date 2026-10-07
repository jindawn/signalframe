package com.signalframe.http;

import com.signalframe.contract.*;
import com.signalframe.analysis.application.AnalysisJobService;
import com.signalframe.news.application.NewsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/news")
public class NewsController {

  private final NewsService news;
  private final AnalysisJobService jobs;

  public NewsController(NewsService news, AnalysisJobService jobs) {
    this.news = news;
    this.jobs = jobs;
  }

  @PostMapping
  ResponseEntity<NewsItem> create(@Valid @RequestBody NewsInput input) {
    var n = news.create(input);
    return ResponseEntity.created(
      java.net.URI.create("/api/v1/news/" + n.id())
    ).body(n);
  }

  @GetMapping
  List<NewsItem> list() {
    return news.recent();
  }

  @GetMapping("/{id}")
  NewsItem get(@PathVariable UUID id) {
    return news.get(id);
  }

  @PostMapping("/{id}/analyze")
  ResponseEntity<AnalysisJob> analyze(
    @PathVariable UUID id,
    HttpServletRequest request
  ) {
    return ResponseEntity.accepted().body(
      jobs.start(id, String.valueOf(request.getAttribute("requestId")))
    );
  }
}
