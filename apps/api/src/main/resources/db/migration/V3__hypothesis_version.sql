-- SCH-14 / M3 (Wave 2B, TASK-06 request). Optimistic concurrency token for the
-- append-only hypothesis timeline: a transition carries the version it read and
-- the write only applies when the stored version still matches, so two concurrent
-- evidence changes cannot silently overwrite each other's confidence event.
--
-- Existing rows receive 0 through the column default, so a client that read a
-- hypothesis before this migration can still transition it with expectedVersion 0.
ALTER TABLE hypotheses ADD COLUMN version integer NOT NULL DEFAULT 0;
