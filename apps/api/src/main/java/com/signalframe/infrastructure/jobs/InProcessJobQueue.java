package com.signalframe.infrastructure.jobs;

import com.signalframe.jobs.domain.JobQueue;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class InProcessJobQueue implements JobQueue {

  private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
    2,
    2,
    0,
    TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(32),
    new ThreadPoolExecutor.AbortPolicy()
  );

  public void enqueue(Runnable task) {
    executor.execute(task);
  }

  @PreDestroy
  public void close() {
    executor.shutdown();
    try {
      if (
        !executor.awaitTermination(5, TimeUnit.SECONDS)
      ) executor.shutdownNow();
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
