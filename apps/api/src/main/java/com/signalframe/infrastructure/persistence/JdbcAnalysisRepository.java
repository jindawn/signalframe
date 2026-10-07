package com.signalframe.infrastructure.persistence;

import com.signalframe.analysis.domain.AnalysisRepository;
import com.signalframe.contract.*;
import com.signalframe.shared.JsonCodec;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAnalysisRepository implements AnalysisRepository {

  private final JdbcTemplate db;
  private final JsonCodec json;

  public JdbcAnalysisRepository(JdbcTemplate db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  @Transactional
  public void complete(Analysis a) {
    String status = db.queryForObject(
      "SELECT status FROM analysis_jobs WHERE id=? FOR UPDATE",
      String.class,
      a.jobId()
    );
    if (
      status.equals("COMPLETED") || status.equals("FAILED")
    ) throw new IllegalStateException("Terminal job");
    db.update(
      "INSERT INTO analyses VALUES(?,?,?,?::jsonb,?)",
      a.id(),
      a.newsId(),
      a.jobId(),
      json.write(a),
      Timestamp.from(a.createdAt())
    );
    for (var h : a.result().hypotheses()) {
      db.update(
        "INSERT INTO hypotheses VALUES(?,?,?::jsonb,?,?,?)",
        h.id(),
        a.id(),
        json.write(h),
        h.confidence(),
        Timestamp.from(h.createdAt()),
        Timestamp.from(h.updatedAt())
      );
      var e = new HypothesisEvent(
        UUID.randomUUID(),
        h.id(),
        "CREATED",
        null,
        h.confidence(),
        h.confidenceReason(),
        h.createdAt()
      );
      db.update(
        "INSERT INTO hypothesis_events VALUES(?,?,?::jsonb,?)",
        e.id(),
        h.id(),
        json.write(e),
        Timestamp.from(e.createdAt())
      );
    }
    for (var i : a.result().verificationIndicators())
      db.update(
        "INSERT INTO indicators VALUES(?,?,?,?::jsonb)",
        i.id(),
        i.predictionId(),
        a.id(),
        json.write(i)
      );
    db.update(
      "UPDATE analysis_jobs SET status='COMPLETED',analysis_id=?,updated_at=now() WHERE id=?",
      a.id(),
      a.jobId()
    );
    db.update(
      "INSERT INTO job_events(job_id,status,step,message,at) VALUES(?,'COMPLETED','Completed','分析已保存',now())",
      a.jobId()
    );
  }

  public java.util.List<Analysis> forNews(UUID id) {
    return db.query(
      "SELECT payload::text FROM analyses WHERE news_id=? ORDER BY created_at DESC LIMIT 100",
      (rs, n) -> json.read(rs.getString(1), Analysis.class),
      id
    );
  }

  public Optional<Analysis> find(UUID id) {
    return db
      .query(
        "SELECT payload::text FROM analyses WHERE id=?",
        (rs, n) -> json.read(rs.getString(1), Analysis.class),
        id
      )
      .stream()
      .findFirst();
  }
}
