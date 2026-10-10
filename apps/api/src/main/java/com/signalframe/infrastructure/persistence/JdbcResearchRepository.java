package com.signalframe.infrastructure.persistence;

import com.signalframe.contract.*;
import com.signalframe.research.domain.ResearchRepository;
import com.signalframe.shared.JsonCodec;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcResearchRepository implements ResearchRepository {

  private final JdbcTemplate db;
  private final JsonCodec json;

  public JdbcResearchRepository(JdbcTemplate db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  public List<Hypothesis> hypotheses() {
    return db.query(
      "SELECT payload::text FROM hypotheses ORDER BY updated_at DESC LIMIT 100",
      (rs, n) -> json.read(rs.getString(1), Hypothesis.class)
    );
  }

  public Optional<HypothesisDetail> hypothesis(UUID id) {
    return db
      .query(
        "SELECT payload::text, version FROM hypotheses WHERE id=?",
        (rs, n) -> new Object[] {
          json.read(rs.getString(1), Hypothesis.class),
          rs.getLong(2),
        },
        id
      )
      .stream()
      .findFirst()
      .map(row -> {
        var h = (Hypothesis) row[0];
        long version = (Long) row[1];
        return new HypothesisDetail(
          h,
          db.query(
            "SELECT payload::text FROM hypothesis_events WHERE hypothesis_id=? ORDER BY created_at",
            (rs, n) -> json.read(rs.getString(1), HypothesisEvent.class),
            id
          ),
          db.query(
            "SELECT payload::text FROM evidence WHERE hypothesis_id=? ORDER BY created_at",
            (rs, n) -> json.read(rs.getString(1), Evidence.class),
            id
          ),
          db.query(
            "SELECT payload::text FROM predictions WHERE hypothesis_id=? ORDER BY expected_by",
            (rs, n) -> json.read(rs.getString(1), Prediction.class),
            id
          ),
          version
        );
      });
  }

  public List<Topic> topics() {
    return db.query(
      "SELECT id,name FROM topics ORDER BY name LIMIT 100",
      (rs, n) -> topicData(rs.getObject(1, UUID.class), rs.getString(2))
    );
  }

  public Optional<Topic> topic(UUID id) {
    return db
      .query(
        "SELECT id,name FROM topics WHERE id=?",
        (rs, n) -> topicData(id, rs.getString(2)),
        id
      )
      .stream()
      .findFirst();
  }

  private Topic topicData(UUID id, String name) {
    return new Topic(
      id,
      name,
      db.query(
        "SELECT news_id FROM topic_news WHERE topic_id=?",
        (rs, n) -> rs.getObject(1, UUID.class),
        id
      ),
      db.query(
        "SELECT hypothesis_id FROM topic_hypotheses WHERE topic_id=?",
        (rs, n) -> rs.getObject(1, UUID.class),
        id
      )
    );
  }
}
