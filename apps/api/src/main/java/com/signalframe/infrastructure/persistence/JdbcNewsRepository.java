package com.signalframe.infrastructure.persistence;

import com.signalframe.contract.*;
import com.signalframe.news.domain.NewsRepository;
import com.signalframe.shared.JsonCodec;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNewsRepository implements NewsRepository {

  private final JdbcTemplate db;
  private final JsonCodec json;

  public JdbcNewsRepository(JdbcTemplate db, JsonCodec json) {
    this.db = db;
    this.json = json;
  }

  @Transactional
  public void save(NewsItem n) {
    var s = n.source();
    db.update(
      "INSERT INTO sources VALUES(?,?,?, ?,?::jsonb,?)",
      s.id(),
      s.url(),
      s.text(),
      s.extractionStatus(),
      json.write(s),
      Timestamp.from(s.createdAt())
    );
    db.update(
      "INSERT INTO news_items VALUES(?,?,?::jsonb,?)",
      n.id(),
      s.id(),
      json.write(n),
      Timestamp.from(n.createdAt())
    );
  }

  public Optional<NewsItem> find(UUID id) {
    return db
      .query(
        "SELECT payload::text FROM news_items WHERE id=?",
        (rs, row) -> json.read(rs.getString(1), NewsItem.class),
        id
      )
      .stream()
      .findFirst();
  }

  public List<NewsItem> recent() {
    return db.query(
      "SELECT payload::text FROM news_items ORDER BY created_at DESC LIMIT 100",
      (rs, row) -> json.read(rs.getString(1), NewsItem.class)
    );
  }
}
