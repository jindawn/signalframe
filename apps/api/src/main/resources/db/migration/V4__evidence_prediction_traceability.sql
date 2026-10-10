-- SCH-14 / M4 (Wave 2B, TASK-07 request). Traceability columns from SCH-11 and
-- SCH-07. Both are nullable and no backfill is performed: historical rows keep
-- meaning "the producing analysis is unknown", which is exactly true (PR-17 —
-- stored payloads are never rewritten).
--
-- `evidence.analysis_id` links an evidence item to the snapshot that introduced
-- it. The FK is to `analyses(id)`; `evidence.source_id` already references
-- `sources(id)` and remains the mandatory source verification of TASK-07.
ALTER TABLE evidence ADD COLUMN analysis_id uuid NULL REFERENCES analyses(id);

-- `predictions.verified_at` records when a verification outcome was decided, so
-- the verification timeline is readable without parsing the JSONB payload.
ALTER TABLE predictions ADD COLUMN verified_at timestamptz NULL;
