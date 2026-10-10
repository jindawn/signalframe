-- SCH-14 / M2 (Wave 2B, TASK-07 request). Predictions are now created `OPEN` as
-- part of the analysis snapshot (SCH-07) and are resolved later by a verification
-- record, so the V1 CHECK must accept `OPEN`.
--
-- The constraint name is the one PostgreSQL generated for the inline CHECK in V1;
-- it was read back from pg_constraint on a database migrated to V1
-- (`predictions_status_check`). Applied migrations are never edited, so V1 keeps
-- its original definition and this migration widens it.
ALTER TABLE predictions DROP CONSTRAINT predictions_status_check;
ALTER TABLE predictions ADD CONSTRAINT predictions_status_check CHECK (
  status IN ('OPEN', 'CONFIRMED', 'REJECTED', 'PARTIAL', 'UNRESOLVED')
);

-- TASK-07's due-unresolved query is `status = 'OPEN' AND expected_by < now()`.
-- The V1 partial index `predictions_unresolved_due` covers status = 'UNRESOLVED'
-- only (the explicit "deadline passed with no data" case), so it cannot serve the
-- new query. This index is added alongside it, not instead of it: both states are
-- queried by due date and both are legitimately small subsets of the table.
CREATE INDEX predictions_open_due ON predictions(expected_by) WHERE status = 'OPEN';
