package com.signalframe.analysis.application;

import com.signalframe.analysis.application.*;
import com.signalframe.contract.*;
import com.signalframe.jobs.domain.*;
import com.signalframe.news.application.NewsService;
import com.signalframe.shared.ApplicationException;
import java.util.*;
import org.slf4j.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class AnalysisJobService {

  private static final Logger log = LoggerFactory.getLogger(
    AnalysisJobService.class
  );
  private final JobRepository jobs;
  private final JobQueue queue;
  private final NewsService news;
  private final AnalysisPipeline pipeline;

  public AnalysisJobService(
    JobRepository jobs,
    JobQueue queue,
    NewsService news,
    AnalysisPipeline pipeline
  ) {
    this.jobs = jobs;
    this.queue = queue;
    this.news = news;
    this.pipeline = pipeline;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    jobs.recoverInterrupted();
  }

  public AnalysisJob start(UUID newsId, String correlationId) {
    var item = news.get(newsId);
    if (item.source().text().isBlank()) throw new ApplicationException(
      409,
      "NEEDS_TEXT",
      "请粘贴正文继续分析。"
    );
    var job = jobs.create(newsId, correlationId);
    try {
      queue.enqueue(() -> {
        if (!jobs.claim(job.id())) return;
        MDC.put("requestId", correlationId);
        MDC.put("jobId", job.id().toString());
        try {
          pipeline.run(new PipelineContext(job.id(), correlationId, item));
        } catch (Exception e) {
          jobs.fail(
            job.id(),
            e instanceof ApplicationException
              ? e.getMessage()
              : "分析失败，请重试。"
          );
          log.warn("Analysis job failed: {}", e.getClass().getSimpleName());
        } finally {
          MDC.clear();
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      jobs.fail(job.id(), "分析队列已满，请稍后重试。");
      throw new ApplicationException(
        503,
        "QUEUE_FULL",
        "分析队列已满，请稍后重试。"
      );
    }
    return job;
  }

  public AnalysisJob get(UUID id) {
    return jobs.find(id).orElseThrow(ApplicationException::missing);
  }
}
