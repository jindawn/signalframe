CREATE TABLE sources (
 id uuid PRIMARY KEY, url text, body text NOT NULL, extraction_status text NOT NULL CHECK (extraction_status IN ('PASTED','EXTRACTED','NEEDS_TEXT')), payload jsonb NOT NULL, created_at timestamptz NOT NULL);
CREATE TABLE news_items (id uuid PRIMARY KEY, source_id uuid NOT NULL REFERENCES sources(id), payload jsonb NOT NULL, created_at timestamptz NOT NULL);
CREATE TABLE analysis_jobs (
 id uuid PRIMARY KEY, news_id uuid NOT NULL REFERENCES news_items(id), status text NOT NULL CHECK (status IN ('QUEUED','NORMALIZING','EXTRACTING_FACTS','ANALYZING','GENERATING_HYPOTHESES','VERIFYING','SYNTHESIZING','COMPLETED','FAILED')),
 analysis_id uuid, error text, correlation_id text NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL);
CREATE UNIQUE INDEX one_active_job_per_news ON analysis_jobs(news_id) WHERE status NOT IN ('COMPLETED','FAILED');
CREATE TABLE job_events (sequence bigserial PRIMARY KEY, job_id uuid NOT NULL REFERENCES analysis_jobs(id), status text NOT NULL, step text NOT NULL, message text NOT NULL, at timestamptz NOT NULL);
CREATE INDEX job_events_job_sequence ON job_events(job_id,sequence);
CREATE TABLE analyses (id uuid PRIMARY KEY, news_id uuid NOT NULL REFERENCES news_items(id), job_id uuid NOT NULL UNIQUE REFERENCES analysis_jobs(id), payload jsonb NOT NULL, created_at timestamptz NOT NULL);
ALTER TABLE analysis_jobs ADD CONSTRAINT analysis_jobs_analysis_fk FOREIGN KEY (analysis_id) REFERENCES analyses(id);
CREATE TABLE hypotheses (id uuid PRIMARY KEY, analysis_id uuid NOT NULL REFERENCES analyses(id), payload jsonb NOT NULL, confidence integer NOT NULL CHECK (confidence BETWEEN 0 AND 100), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL);
CREATE TABLE hypothesis_events (id uuid PRIMARY KEY, hypothesis_id uuid NOT NULL REFERENCES hypotheses(id), payload jsonb NOT NULL, created_at timestamptz NOT NULL);
CREATE TABLE evidence (id uuid PRIMARY KEY, hypothesis_id uuid NOT NULL REFERENCES hypotheses(id), source_id uuid NOT NULL REFERENCES sources(id), stance text NOT NULL CHECK (stance IN ('SUPPORTS','CONTRADICTS','NEUTRAL')), strength integer NOT NULL CHECK(strength BETWEEN 0 AND 100), payload jsonb NOT NULL, created_at timestamptz NOT NULL);
CREATE INDEX evidence_hypothesis_stance ON evidence(hypothesis_id,stance);
CREATE TABLE predictions (id uuid PRIMARY KEY, hypothesis_id uuid NOT NULL REFERENCES hypotheses(id), expected_by timestamptz NOT NULL, status text NOT NULL CHECK(status IN ('CONFIRMED','REJECTED','PARTIAL','UNRESOLVED')), payload jsonb NOT NULL);
CREATE INDEX predictions_unresolved_due ON predictions(expected_by) WHERE status='UNRESOLVED';
CREATE TABLE indicators (id uuid PRIMARY KEY, prediction_id uuid REFERENCES predictions(id), analysis_id uuid NOT NULL REFERENCES analyses(id), payload jsonb NOT NULL);
CREATE TABLE topics (id uuid PRIMARY KEY, name text NOT NULL UNIQUE);
CREATE TABLE topic_news (topic_id uuid REFERENCES topics(id), news_id uuid REFERENCES news_items(id), PRIMARY KEY(topic_id,news_id));
CREATE TABLE topic_hypotheses (topic_id uuid REFERENCES topics(id), hypothesis_id uuid REFERENCES hypotheses(id), PRIMARY KEY(topic_id,hypothesis_id));
CREATE TABLE model_runs (id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES analysis_jobs(id), purpose text NOT NULL, provider text NOT NULL, model text NOT NULL, status text NOT NULL CHECK(status IN ('SUCCEEDED','FAILED')), payload jsonb NOT NULL, started_at timestamptz NOT NULL);
CREATE INDEX model_runs_job ON model_runs(job_id,started_at);
