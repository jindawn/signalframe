package com.signalframe.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.signalframe.contract.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TASK-10: queue overload is an explicit, recoverable failure.
 *
 * <p>The production queue is bounded (2 workers, 32 waiting slots, abort policy).
 * This test occupies the real {@code JobQueue} bean exactly the way a burst of
 * real jobs would, then drives the HTTP path that a user drives, and asserts both
 * the response and the persisted job state. It is deliberately not a unit test of
 * the executor: the assertion is that the <em>whole</em> path — controller,
 * service, repository, PostgreSQL — reports the overload honestly.
 */
class QueueOverloadIntegrationTest extends IntegrationFlowSupport {

  private static final int WORKERS = 2;
  private static final int SLOTS = 32;

  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch workersBusy = new CountDownLatch(WORKERS);

  @AfterEach
  void drain() {
    // Never leave a blocked worker behind: the shared Spring context outlives
    // this class.
    release.countDown();
  }

  @Test
  void saturatedQueueRejectsTheJobExplicitlyAndStaysRecoverable()
    throws Exception {
    occupyWorkers();
    for (int i = 0; i < SLOTS; i++) queue.enqueue(this::holdUntilReleased);

    var news = newsText("队列过载：第 " + (WORKERS + SLOTS + 1) + " 个任务必须被显式拒绝。");
    var rejected = post("/api/v1/news/" + news.id() + "/analyze", null);
    assertError(rejected, 503, "QUEUE_FULL");

    var failed = jobs.find(latestJobId(news.id())).orElseThrow();
    assertEquals(JobStatus.FAILED, failed.status());
    assertTrue(
      failed.error().contains("队列已满"),
      "the persisted job must explain the overload: " + failed.error()
    );
    assertEquals(JobStatus.FAILED, failed.events().getLast().status());
    assertEquals(
      0,
      countRows("SELECT count(*) FROM analyses WHERE job_id=?", failed.id()),
      "a rejected job must not persist a partial analysis"
    );

    // The rejection is transient: once the queue drains, the same news is
    // analyzable again, because FAILED jobs do not hold the active-job slot.
    release.countDown();
    var retried = awaitTerminal(analyze(news.id()).id(), Duration.ofSeconds(60));
    assertEquals(JobStatus.COMPLETED, retried.status(), retried.error());
    assertNotEquals(
      failed.id(),
      retried.id(),
      "the retry must be a new durable job, not a reuse of the failed row"
    );
  }

  private void occupyWorkers() throws InterruptedException {
    for (int i = 0; i < WORKERS; i++) queue.enqueue(() -> {
      workersBusy.countDown();
      holdUntilReleased();
    });
    assertTrue(
      workersBusy.await(10, TimeUnit.SECONDS),
      "both worker threads must be occupied before the overload is asserted"
    );
  }

  private void holdUntilReleased() {
    try {
      release.await(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
