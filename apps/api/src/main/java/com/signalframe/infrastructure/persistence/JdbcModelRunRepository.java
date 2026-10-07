package com.signalframe.infrastructure.persistence;

import com.signalframe.ai.domain.ModelRunRepository;
import com.signalframe.contract.ModelRun;
import com.signalframe.shared.JsonCodec;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcModelRunRepository implements ModelRunRepository {

  private final JdbcTemplate db;
  private final JsonCodec json;

  public JdbcModelRunRepository(JdbcTemplate db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  public void save(ModelRun r) {
    db.update(
      "INSERT INTO model_runs VALUES(?,?,?,?,?,?,?::jsonb,?)",
      r.id(),
      r.jobId(),
      r.purpose().name(),
      r.provider(),
      r.model(),
      r.status(),
      json.write(r),
      Timestamp.from(r.startedAt())
    );
  }

  public List<ModelRun> recent(UUID jobId) {
    return jobId == null
      ? db.query(
          "SELECT payload::text FROM model_runs ORDER BY started_at DESC LIMIT 100",
          (rs, n) -> json.read(rs.getString(1), ModelRun.class)
        )
      : db.query(
          "SELECT payload::text FROM model_runs WHERE job_id=? ORDER BY started_at DESC LIMIT 100",
          (rs, n) -> json.read(rs.getString(1), ModelRun.class),
          jobId
        );
  }
}
