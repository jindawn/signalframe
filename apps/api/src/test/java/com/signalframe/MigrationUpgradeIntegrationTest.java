package com.signalframe;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that the additive Wave 2B migrations {@code V2__prediction_open_status},
 * {@code V3__hypothesis_version} and {@code V4__evidence_prediction_traceability}
 * apply to a database that already holds V1 data <em>without rewriting that data</em>
 * (PR-17: stored payloads and legacy column values are never mutated by a migration).
 *
 * <p>The test migrates a throwaway PostgreSQL container to V1 with the Flyway API,
 * inserts a small but complete legacy graph (source, news item, analysis job,
 * analysis, hypothesis with confidence 42, hypothesis event, evidence, prediction),
 * then runs Flyway again to latest (V4) and asserts on the widened contract:
 *
 * <ul>
 *   <li>legacy values survive unchanged (confidence 42, prediction {@code CONFIRMED},
 *       evidence {@code source_id}, legacy {@code version} 0);</li>
 *   <li>{@code hypotheses.version} exists, is {@code NOT NULL}, defaults to 0 and reads 0
 *       for the legacy row;</li>
 *   <li>{@code predictions_status_check} accepts {@code OPEN} and still rejects a bogus
 *       status — this also guards the exact constraint name, which is the name PostgreSQL
 *       generated for the inline V1 CHECK and the name V2 drops;</li>
 *   <li>{@code evidence.analysis_id} is nullable, accepts the legacy analysis id and is
 *       rejected by its foreign key for an unknown id;</li>
 *   <li>{@code predictions.verified_at} is nullable (legacy row {@code NULL}) and accepts a
 *       {@code timestamptz};</li>
 *   <li>the due-OPEN query returns only the due {@code OPEN} row and, with sequential scans
 *       disabled, plans through the new partial index {@code predictions_open_due}, which
 *       coexists with the pre-existing {@code predictions_unresolved_due}.</li>
 * </ul>
 *
 * <p>It is intentionally a plain JUnit 5 test with raw JDBC (no {@code @SpringBootTest}) so
 * it stays independent of the application context and of the generated contract records.
 */
class MigrationUpgradeIntegrationTest {

  static final PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:17.6-alpine"
  );

  static {
    postgres.start();
  }

  static final UUID LEGACY_SOURCE = UUID.fromString(
    "11111111-1111-1111-1111-111111111111"
  );
  static final UUID LEGACY_NEWS = UUID.fromString(
    "22222222-2222-2222-2222-222222222222"
  );
  static final UUID LEGACY_JOB = UUID.fromString(
    "33333333-3333-3333-3333-333333333333"
  );
  static final UUID LEGACY_ANALYSIS = UUID.fromString(
    "44444444-4444-4444-4444-444444444444"
  );
  static final UUID LEGACY_HYPOTHESIS = UUID.fromString(
    "55555555-5555-5555-5555-555555555555"
  );
  static final UUID LEGACY_HYPOTHESIS_EVENT = UUID.fromString(
    "66666666-6666-6666-6666-666666666666"
  );
  static final UUID LEGACY_EVIDENCE = UUID.fromString(
    "77777777-7777-7777-7777-777777777777"
  );
  static final UUID LEGACY_PREDICTION = UUID.fromString(
    "88888888-8888-8888-8888-888888888888"
  );
  static final UUID OPEN_DUE_PREDICTION = UUID.fromString(
    "99999999-9999-9999-9999-999999999991"
  );
  static final UUID OPEN_FUTURE_PREDICTION = UUID.fromString(
    "99999999-9999-9999-9999-999999999992"
  );
  static final UUID UNKNOWN_ANALYSIS = UUID.fromString(
    "00000000-0000-0000-0000-0000000000ff"
  );

  @Test
  void additiveMigrationsPreserveV1DataAndWidenContract() throws Exception {
    MigrateResult toV1 = flyway().target("1").load().migrate();
    assertTrue(toV1.success, "migration to V1 must succeed");
    assertEquals(1, toV1.migrationsExecuted);
    assertEquals("1", toV1.targetSchemaVersion);

    try (Connection conn = connect()) {
      insertLegacyGraph(conn);
    }

    MigrateResult toLatest = flyway().load().migrate();
    assertTrue(toLatest.success, "migration to latest must succeed");
    assertEquals(3, toLatest.migrationsExecuted, "V2, V3 and V4 must apply");
    assertEquals("4", toLatest.targetSchemaVersion);

    try (Connection conn = connect()) {
      assertFlywayHistoryIsOneToFour(conn);
      assertLegacyValuesUnchanged(conn);
      assertHypothesisVersionColumn(conn);
      assertEvidenceAnalysisIdColumn(conn);
      assertVerifiedAtColumn(conn);
      assertOpenStatusWidened(conn);
      assertDueOpenQueryAndIndex(conn);
      assertBothPartialIndexesExist(conn);
    }
  }

  private static FluentConfiguration flyway() {
    return org.flywaydb.core.Flyway.configure()
      .dataSource(
        postgres.getJdbcUrl(),
        postgres.getUsername(),
        postgres.getPassword()
      )
      .locations("classpath:db/migration");
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
      postgres.getJdbcUrl(),
      postgres.getUsername(),
      postgres.getPassword()
    );
  }

  private static void insertLegacyGraph(Connection conn) throws SQLException {
    exec(
      conn,
      "INSERT INTO sources(id,url,body,extraction_status,payload,created_at) VALUES (?,?,?,?,?::jsonb,?)",
      LEGACY_SOURCE,
      "https://example.test/legacy",
      "legacy body",
      "PASTED",
      "{\"legacy\":true}",
      OffsetDateTime.parse("2024-01-01T00:00:00Z")
    );
    exec(
      conn,
      "INSERT INTO news_items(id,source_id,payload,created_at) VALUES (?,?,?::jsonb,?)",
      LEGACY_NEWS,
      LEGACY_SOURCE,
      "{\"legacy\":true}",
      OffsetDateTime.parse("2024-01-01T00:00:00Z")
    );
    exec(
      conn,
      "INSERT INTO analysis_jobs(id,news_id,status,correlation_id,created_at,updated_at) VALUES (?,?,?,?,?,?)",
      LEGACY_JOB,
      LEGACY_NEWS,
      "COMPLETED",
      "legacy",
      OffsetDateTime.parse("2024-01-01T00:00:00Z"),
      OffsetDateTime.parse("2024-01-01T00:00:00Z")
    );
    exec(
      conn,
      "INSERT INTO analyses(id,news_id,job_id,payload,created_at) VALUES (?,?,?,?::jsonb,?)",
      LEGACY_ANALYSIS,
      LEGACY_NEWS,
      LEGACY_JOB,
      "{\"legacy\":true}",
      OffsetDateTime.parse("2024-01-01T00:00:00Z")
    );
    exec(
      conn,
      "INSERT INTO hypotheses(id,analysis_id,payload,confidence,created_at,updated_at) VALUES (?,?,?::jsonb,?,?,?)",
      LEGACY_HYPOTHESIS,
      LEGACY_ANALYSIS,
      "{\"legacy\":true}",
      42,
      OffsetDateTime.parse("2024-01-01T00:00:00Z"),
      OffsetDateTime.parse("2024-01-01T00:00:00Z")
    );
    exec(
      conn,
      "INSERT INTO hypothesis_events(id,hypothesis_id,payload,created_at) VALUES (?,?,?::jsonb,?)",
      LEGACY_HYPOTHESIS_EVENT,
      LEGACY_HYPOTHESIS,
      "{\"legacy\":true}",
      OffsetDateTime.parse("2024-01-01T00:00:00Z")
    );
    exec(
      conn,
      "INSERT INTO evidence(id,hypothesis_id,source_id,stance,strength,payload,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
      LEGACY_EVIDENCE,
      LEGACY_HYPOTHESIS,
      LEGACY_SOURCE,
      "SUPPORTS",
      50,
      "{\"legacy\":true}",
      OffsetDateTime.parse("2024-01-01T00:00:00Z")
    );
    exec(
      conn,
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES (?,?,?,?,?::jsonb)",
      LEGACY_PREDICTION,
      LEGACY_HYPOTHESIS,
      OffsetDateTime.parse("2024-02-01T00:00:00Z"),
      "CONFIRMED",
      "{\"legacy\":true}"
    );
  }

  private static void assertFlywayHistoryIsOneToFour(Connection conn)
    throws SQLException {
    List<String> versions = strings(
      conn,
      "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank"
    );
    assertEquals(List.of("1", "2", "3", "4"), versions);
  }

  private static void assertLegacyValuesUnchanged(Connection conn)
    throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT confidence, version FROM hypotheses WHERE id = ?"
    )) {
      ps.setObject(1, LEGACY_HYPOTHESIS);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(42, rs.getInt("confidence"));
        assertEquals(0, rs.getInt("version"));
      }
    }
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT status FROM predictions WHERE id = ?"
    )) {
      ps.setObject(1, LEGACY_PREDICTION);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("CONFIRMED", rs.getString("status"));
      }
    }
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT source_id FROM evidence WHERE id = ?"
    )) {
      ps.setObject(1, LEGACY_EVIDENCE);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(LEGACY_SOURCE, rs.getObject("source_id", UUID.class));
      }
    }
  }

  private static void assertHypothesisVersionColumn(Connection conn)
    throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT is_nullable, data_type, column_default FROM information_schema.columns" +
      " WHERE table_name = 'hypotheses' AND column_name = 'version'"
    )) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "hypotheses.version must exist");
        assertEquals("NO", rs.getString("is_nullable"));
        assertEquals("integer", rs.getString("data_type"));
        String def = rs.getString("column_default");
        assertNotNull(def, "hypotheses.version must have a default");
        assertTrue(def.contains("0"), "default must be 0 but was " + def);
      }
    }
  }

  private static void assertEvidenceAnalysisIdColumn(Connection conn)
    throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT is_nullable, data_type FROM information_schema.columns" +
      " WHERE table_name = 'evidence' AND column_name = 'analysis_id'"
    )) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "evidence.analysis_id must exist");
        assertEquals("YES", rs.getString("is_nullable"));
        assertEquals("uuid", rs.getString("data_type"));
      }
    }
    // Legacy row kept the column unset by the migration ...
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT analysis_id FROM evidence WHERE id = ?"
    )) {
      ps.setObject(1, LEGACY_EVIDENCE);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertNull(rs.getObject("analysis_id"));
      }
    }
    // ... and it accepts the legacy analysis id.
    exec(
      conn,
      "UPDATE evidence SET analysis_id = ? WHERE id = ?",
      LEGACY_ANALYSIS,
      LEGACY_EVIDENCE
    );
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT analysis_id FROM evidence WHERE id = ?"
    )) {
      ps.setObject(1, LEGACY_EVIDENCE);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(LEGACY_ANALYSIS, rs.getObject("analysis_id", UUID.class));
      }
    }
    // ... and the FK rejects an unknown analysis id.
    assertThrows(SQLException.class, () ->
      exec(
        conn,
        "UPDATE evidence SET analysis_id = ? WHERE id = ?",
        UNKNOWN_ANALYSIS,
        LEGACY_EVIDENCE
      )
    );
  }

  private static void assertVerifiedAtColumn(Connection conn)
    throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT is_nullable, data_type FROM information_schema.columns" +
      " WHERE table_name = 'predictions' AND column_name = 'verified_at'"
    )) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "predictions.verified_at must exist");
        assertEquals("YES", rs.getString("is_nullable"));
        assertEquals("timestamp with time zone", rs.getString("data_type"));
      }
    }
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT verified_at FROM predictions WHERE id = ?"
    )) {
      ps.setObject(1, LEGACY_PREDICTION);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertNull(rs.getObject("verified_at"), "legacy verified_at must be NULL");
      }
    }
    OffsetDateTime verifiedAt = OffsetDateTime.parse("2024-03-01T12:00:00Z");
    exec(
      conn,
      "UPDATE predictions SET verified_at = ? WHERE id = ?",
      verifiedAt,
      LEGACY_PREDICTION
    );
    try (PreparedStatement ps = conn.prepareStatement(
      "SELECT verified_at FROM predictions WHERE id = ?"
    )) {
      ps.setObject(1, LEGACY_PREDICTION);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(
          verifiedAt.toInstant(),
          rs.getObject("verified_at", OffsetDateTime.class).toInstant()
        );
      }
    }
  }

  private static void assertOpenStatusWidened(Connection conn)
    throws SQLException {
    // The widened constraint accepts OPEN; one row is due (past) and one is not (future).
    exec(
      conn,
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES (?,?, now() - interval '1 day','OPEN',?::jsonb)",
      OPEN_DUE_PREDICTION,
      LEGACY_HYPOTHESIS,
      "{\"open\":\"due\"}"
    );
    exec(
      conn,
      "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES (?,?, now() + interval '1 day','OPEN',?::jsonb)",
      OPEN_FUTURE_PREDICTION,
      LEGACY_HYPOTHESIS,
      "{\"open\":\"future\"}"
    );
    assertEquals(
      "OPEN",
      scalarString(
        conn,
        "SELECT status FROM predictions WHERE id = ?",
        OPEN_DUE_PREDICTION
      )
    );
    // ... and the same named constraint still rejects a status outside the widened list.
    assertThrows(SQLException.class, () ->
      exec(
        conn,
        "INSERT INTO predictions(id,hypothesis_id,expected_by,status,payload) VALUES (?,?, now(),'BOGUS','{}'::jsonb)",
        UUID.fromString("99999999-9999-9999-9999-999999999993"),
        LEGACY_HYPOTHESIS
      )
    );
  }

  private static void assertDueOpenQueryAndIndex(Connection conn)
    throws SQLException {
    List<UUID> due = uuids(
      conn,
      "SELECT id FROM predictions WHERE status = 'OPEN' AND expected_by < now() ORDER BY id"
    );
    assertEquals(
      List.of(OPEN_DUE_PREDICTION),
      due,
      "the due-OPEN query must return exactly the past-due OPEN row"
    );

    // The table is tiny, so the planner prefers a sequential scan; disable it to make
    // the assertion about the available partial index deterministic.
    exec(conn, "SET enable_seqscan = off");
    String plan = String.join(
      "\n",
      strings(
        conn,
        "EXPLAIN SELECT id FROM predictions WHERE status = 'OPEN' AND expected_by < now()"
      )
    );
    assertTrue(
      plan.contains("predictions_open_due"),
      "due-OPEN plan must use the partial index, was:\n" + plan
    );
  }

  private static void assertBothPartialIndexesExist(Connection conn)
    throws SQLException {
    List<String> indexes = strings(
      conn,
      "SELECT indexname FROM pg_indexes WHERE tablename = 'predictions'" +
      " AND indexname IN ('predictions_open_due','predictions_unresolved_due') ORDER BY indexname"
    );
    assertEquals(
      List.of("predictions_open_due", "predictions_unresolved_due"),
      indexes
    );
  }

  private static void exec(Connection conn, String sql, Object... args)
    throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bind(ps, args);
      ps.executeUpdate();
    }
  }

  private static void bind(PreparedStatement ps, Object... args)
    throws SQLException {
    for (int i = 0; i < args.length; i++) {
      ps.setObject(i + 1, args[i]);
    }
  }

  private static String scalarString(
    Connection conn,
    String sql,
    Object... args
  ) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "query returned no row: " + sql);
        return rs.getString(1);
      }
    }
  }

  private static List<String> strings(
    Connection conn,
    String sql,
    Object... args
  ) throws SQLException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static List<UUID> uuids(
    Connection conn,
    String sql,
    Object... args
  ) throws SQLException {
    List<UUID> out = new ArrayList<>();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      bind(ps, args);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) out.add(rs.getObject(1, UUID.class));
      }
    }
    return out;
  }
}
