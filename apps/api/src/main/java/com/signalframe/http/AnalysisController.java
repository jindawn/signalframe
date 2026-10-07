package com.signalframe.http;

import com.signalframe.analysis.domain.AnalysisRepository;
import com.signalframe.contract.*;
import com.signalframe.analysis.application.AnalysisJobService;
import com.signalframe.shared.ApplicationException;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class AnalysisController {

  private final AnalysisJobService jobs;
  private final AnalysisRepository analyses;
  private final ScheduledExecutorService streams =
    Executors.newScheduledThreadPool(2);

  public AnalysisController(
    AnalysisJobService jobs,
    AnalysisRepository analyses
  ) {
    this.jobs = jobs;
    this.analyses = analyses;
  }

  @GetMapping("/news/{id}/analyses")
  List<Analysis> forNews(@PathVariable UUID id) {
    return analyses.forNews(id);
  }

  @GetMapping("/analyses/{id}")
  Analysis analysis(@PathVariable UUID id) {
    return analyses.find(id).orElseThrow(ApplicationException::missing);
  }

  @GetMapping("/analysis-jobs/{id}")
  AnalysisJob job(@PathVariable UUID id) {
    return jobs.get(id);
  }

  @GetMapping(
    value = "/analysis-jobs/{id}/events",
    produces = "text/event-stream"
  )
  SseEmitter events(
    @PathVariable UUID id,
    @RequestHeader(value = "Last-Event-ID", defaultValue = "0") long last
  ) {
    jobs.get(id);
    var emitter = new SseEmitter(120000L);
    var cursor = new AtomicLong(last);
    var done = new AtomicBoolean(false);
    var future = new AtomicReference<ScheduledFuture<?>>();
    Runnable cancel = () -> {
      done.set(true);
      var f = future.get();
      if (f != null) f.cancel(false);
    };
    emitter.onCompletion(cancel);
    emitter.onTimeout(() -> {
      cancel.run();
      emitter.complete();
    });
    emitter.onError(e -> cancel.run());
    future.set(
      streams.scheduleAtFixedRate(
        () -> {
          if (done.get()) return;
          try {
            var job = jobs.get(id);
            for (var e : job.events())
              if (e.sequence() > cursor.get()) {
                emitter.send(
                  SseEmitter.event()
                    .id(e.sequence().toString())
                    .name("progress")
                    .data(e)
                );
                cursor.set(e.sequence());
              }
            if (
              job.status() == JobStatus.COMPLETED ||
              job.status() == JobStatus.FAILED
            ) {
              cancel.run();
              emitter.complete();
            }
          } catch (Exception e) {
            cancel.run();
            emitter.completeWithError(e);
          }
        },
        100,
        300,
        TimeUnit.MILLISECONDS
      )
    );
    return emitter;
  }

  @PreDestroy
  public void close() {
    streams.shutdownNow();
  }
}
