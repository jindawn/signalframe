package com.signalframe.infrastructure.persistence;

import com.signalframe.contract.*;
import com.signalframe.jobs.domain.JobRepository;
import com.signalframe.shared.ApplicationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcJobRepository implements JobRepository {

  private final JdbcTemplate db;

  public JdbcJobRepository(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional
  public AnalysisJob create(UUID newsId, String correlationId) {
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    try {
      db.update(
        "INSERT INTO analysis_jobs(id,news_id,status,correlation_id,created_at,updated_at) VALUES(?,?,'QUEUED',?,?,?)",
        id,
        newsId,
        correlationId,
        Timestamp.from(now),
        Timestamp.from(now)
      );
    } catch (DuplicateKeyException e) {
      throw new ApplicationException(
        409,
        "JOB_ACTIVE",
        "这条新闻已有分析任务，请等待完成。"
      );
    }
    event(id, JobStatus.QUEUED, "Queued", "等待分析");
    return find(id).orElseThrow();
  }

  @Transactional(
    readOnly = true,
    isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ
  )
  public Optional<AnalysisJob> find(UUID id) {
    var events = db.query(
      "SELECT sequence,status,step,message,at FROM job_events WHERE job_id=? ORDER BY sequence",
      (rs, n) ->
        new JobEvent(
          rs.getLong(1),
          JobStatus.valueOf(rs.getString(2)),
          rs.getString(3),
          rs.getString(4),
          rs.getTimestamp(5).toInstant()
        ),
      id
    );
    return db
      .query(
        "SELECT * FROM analysis_jobs WHERE id=?",
        (rs, n) ->
          new AnalysisJob(
            id,
            rs.getObject("news_id", UUID.class),
            JobStatus.valueOf(rs.getString("status")),
            rs.getObject("analysis_id", UUID.class),
            rs.getString("error"),
            rs.getString("correlation_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            events
          ),
        id
      )
      .stream()
      .findFirst();
  }

  public boolean claim(UUID id) {
    return (
      db.update(
        "UPDATE analysis_jobs SET status='NORMALIZING', updated_at=now() WHERE id=? AND status='QUEUED'",
        id
      ) == 1
    );
  }

  @Transactional
  public void progress(UUID id, JobStatus status, String step, String message) {
    db.queryForObject(
      "SELECT status FROM analysis_jobs WHERE id=? FOR UPDATE",
      String.class,
      id
    );
    int updated = db.update(
      "UPDATE analysis_jobs SET status=?,updated_at=now() WHERE id=? AND status NOT IN ('COMPLETED','FAILED')",
      status.name(),
      id
    );
    if (updated == 1) event(id, status, step, message);
  }

  @Transactional
  public void fail(UUID id, String message) {
    db.queryForObject(
      "SELECT status FROM analysis_jobs WHERE id=? FOR UPDATE",
      String.class,
      id
    );
    if (
      db.update(
        "UPDATE analysis_jobs SET status='FAILED',error=?,updated_at=now() WHERE id=? AND status NOT IN ('COMPLETED','FAILED')",
        message,
        id
      ) == 1
    ) event(id, JobStatus.FAILED, "Failed", message);
  }

  @Transactional
  public void recoverInterrupted() {
    var ids = db.query(
      "UPDATE analysis_jobs SET status='FAILED',error='服务重启中断了任务，请重新分析。',updated_at=now() WHERE status NOT IN ('COMPLETED','FAILED') RETURNING id",
      (rs, n) -> rs.getObject(1, UUID.class)
    );
    ids.forEach(id ->
      event(
        id,
        JobStatus.FAILED,
        "Recovery",
        "服务重启中断了任务，请重新分析。"
      )
    );
  }

  private void event(UUID id, JobStatus status, String step, String message) {
    db.update(
      "INSERT INTO job_events(job_id,status,step,message,at) VALUES(?,?,?,?,now())",
      id,
      status.name(),
      step,
      message
    );
  }
}
