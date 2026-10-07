package com.signalframe.ai.application.observability;

import com.signalframe.ai.domain.observability.*;
import com.signalframe.contract.ModelRun;
import com.signalframe.shared.JsonCodec;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only JDBC adapter for {@link ModelObservabilityQuery}.
 *
 * <p>Ownership note: this task owns the ai observability packages and treats the
 * datasource as a read-only dependency, so the adapter sits beside the service
 * rather than under infrastructure/persistence. It imports no infrastructure
 * type, issues SELECT statements only and never touches prompt or secret data:
 * the audit table stores provider, model, purpose, prompt version, usage, cost,
 * status and correlation identifiers.
 */
@Component
public class JdbcModelRunObservabilityQuery implements ModelObservabilityQuery {

  private final JdbcTemplate db;
  private final JsonCodec json;

  public JdbcModelRunObservabilityQuery(JdbcTemplate db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  @Override
  public List<ModelRun> runs(ModelRunFilter filter, int limit) {
    var sql = new StringBuilder(
      "SELECT payload::text FROM model_runs WHERE TRUE"
    );
    var args = new ArrayList<Object>();
    if (filter.from() != null) {
      sql.append(" AND started_at >= ?");
      args.add(Timestamp.from(filter.from()));
    }
    if (filter.to() != null) {
      sql.append(" AND started_at < ?");
      args.add(Timestamp.from(filter.to()));
    }
    if (filter.jobId() != null) {
      sql.append(" AND job_id = ?");
      args.add(filter.jobId());
    }
    if (filter.provider() != null) {
      sql.append(" AND provider = ?");
      args.add(filter.provider());
    }
    if (filter.model() != null) {
      sql.append(" AND model = ?");
      args.add(filter.model());
    }
    if (filter.purpose() != null) {
      sql.append(" AND purpose = ?");
      args.add(filter.purpose().name());
    }
    sql.append(" ORDER BY started_at ASC, id ASC LIMIT ?");
    args.add(limit);
    return db.query(
      sql.toString(),
      (rs, row) -> json.read(rs.getString(1), ModelRun.class),
      args.toArray()
    );
  }
}
