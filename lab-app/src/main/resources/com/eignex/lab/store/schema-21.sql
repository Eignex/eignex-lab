-- The lab database at schema version 21, the baseline every later step builds on. Statements start at column 0.
CREATE TABLE jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ref TEXT NOT NULL, sha TEXT,
                status TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
                setup_done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, started_at INTEGER,
                finished_at INTEGER, error TEXT, parallel INTEGER NOT NULL DEFAULT 1,
                priority INTEGER NOT NULL DEFAULT 0, paused INTEGER NOT NULL DEFAULT 0, experiment TEXT,
                run_ms INTEGER NOT NULL DEFAULT 0, running_since INTEGER, local INTEGER NOT NULL DEFAULT 0, backend TEXT NOT NULL DEFAULT 'nomad', host TEXT NOT NULL DEFAULT 'lab', analysis INTEGER NOT NULL DEFAULT 0, bench INTEGER NOT NULL DEFAULT 0, measurement TEXT NOT NULL DEFAULT 'none', origin TEXT NOT NULL DEFAULT 'submitted', series TEXT, deadline_from_ms INTEGER NOT NULL DEFAULT 0);
CREATE TABLE commands (
                job_id INTEGER NOT NULL, idx INTEGER NOT NULL, cmd TEXT NOT NULL, timeout_sec INTEGER NOT NULL,
                status TEXT NOT NULL, exit_code INTEGER, started_at INTEGER, finished_at INTEGER,
                cores INTEGER NOT NULL DEFAULT 1, phase TEXT, host TEXT, PRIMARY KEY (job_id, idx));
CREATE TABLE schedules (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ref TEXT NOT NULL,
                parallel INTEGER NOT NULL, priority INTEGER NOT NULL, interval_sec INTEGER NOT NULL,
                last_sha TEXT, last_job INTEGER, checked_at INTEGER, experiment TEXT NOT NULL, launch INTEGER NOT NULL DEFAULT 0);
CREATE TABLE arms (
                job_id INTEGER NOT NULL, idx INTEGER NOT NULL, label TEXT NOT NULL, arm TEXT NOT NULL,
                sha TEXT NOT NULL, PRIMARY KEY (job_id, idx));
CREATE TABLE problems (
                job_id INTEGER NOT NULL, idx INTEGER NOT NULL, problem TEXT NOT NULL, PRIMARY KEY (job_id, idx));
CREATE TABLE cases (
                job_id INTEGER NOT NULL, idx INTEGER NOT NULL, problem_idx INTEGER NOT NULL,
                arm_idx INTEGER NOT NULL, seed INTEGER, record TEXT, repeat INTEGER NOT NULL DEFAULT 0, lab_check TEXT, stdout TEXT,
                PRIMARY KEY (job_id, idx));
CREATE TABLE provenances (sha TEXT PRIMARY KEY, provenance TEXT NOT NULL);
CREATE TABLE compaction (id INTEGER PRIMARY KEY CHECK (id = 0), state TEXT NOT NULL);
CREATE TABLE reference_rows (
                collection TEXT NOT NULL, problem TEXT NOT NULL, solver TEXT NOT NULL, maximize INTEGER NOT NULL,
                objective REAL, feasible INTEGER, proven INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL,
                budget_ms INTEGER NOT NULL, source TEXT NOT NULL, updated_at INTEGER NOT NULL,
                version TEXT NOT NULL DEFAULT '', validation TEXT, dual_bound REAL, stale INTEGER NOT NULL DEFAULT 0,
                source_identity TEXT, PRIMARY KEY (collection, problem, solver));
CREATE TABLE problem_features (
                collection TEXT NOT NULL, problem TEXT NOT NULL, themes TEXT NOT NULL, structure TEXT, logic TEXT,
                PRIMARY KEY (collection, problem));
CREATE TABLE holds (
                id TEXT PRIMARY KEY, job_id INTEGER NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
CREATE INDEX reference_rows_updated ON reference_rows (updated_at);
CREATE INDEX problems_identity ON problems (json_extract(problem, '$.collection'), json_extract(problem, '$.problem'));
CREATE TABLE execution_sessions (id TEXT PRIMARY KEY, job_id INTEGER NOT NULL, generation INTEGER NOT NULL, execution_class TEXT NOT NULL, release TEXT NOT NULL, budget TEXT NOT NULL, nomad_job_id TEXT NOT NULL UNIQUE, desired TEXT NOT NULL, token_hash TEXT NOT NULL, allocation_id TEXT, node_id TEXT, created_at INTEGER NOT NULL, observed_at INTEGER, observation TEXT, manifest TEXT, cpu_enforcement TEXT, placement TEXT, preparation_slots INTEGER, resource_checkpoint TEXT);
CREATE TABLE iteration_attempts (job_id INTEGER NOT NULL, iteration_id TEXT NOT NULL, generation INTEGER NOT NULL, session_id TEXT NOT NULL, allocation_id TEXT NOT NULL, token_hash TEXT NOT NULL, lease_until INTEGER NOT NULL, state TEXT NOT NULL, indices TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, result TEXT, preparation_progress TEXT, preparation_caches TEXT, preparation_updated_at INTEGER, parallel_limit INTEGER NOT NULL DEFAULT 1, preparation_diagnostics TEXT, result_sha256 TEXT, PRIMARY KEY (job_id, iteration_id, generation));
CREATE UNIQUE INDEX active_iteration ON iteration_attempts (job_id, iteration_id) WHERE state IN ('claimed', 'preparing', 'executing', 'uploading');
CREATE UNIQUE INDEX active_session_iteration ON iteration_attempts (session_id) WHERE state IN ('claimed', 'preparing', 'executing', 'uploading');
CREATE TABLE execution_outbox (id TEXT PRIMARY KEY, job_id INTEGER NOT NULL, session_id TEXT NOT NULL, operation TEXT NOT NULL, payload TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0);
CREATE TABLE execution_selections (job_id INTEGER NOT NULL, generation INTEGER NOT NULL, request_hash TEXT NOT NULL, selected TEXT NOT NULL, PRIMARY KEY (job_id, generation));
CREATE TABLE execution_queue_recipes (job_id INTEGER PRIMARY KEY, settings TEXT NOT NULL);
CREATE TABLE execution_events (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL, session_id TEXT, detail TEXT NOT NULL, at INTEGER NOT NULL);
CREATE TABLE capacity_leases (id TEXT PRIMARY KEY, pool TEXT NOT NULL, nodes INTEGER NOT NULL, expires_at INTEGER NOT NULL);
CREATE TABLE execution_ingestion (job_id INTEGER NOT NULL, iteration_id TEXT NOT NULL, PRIMARY KEY (job_id, iteration_id));
CREATE TABLE execution_checks (job_id INTEGER NOT NULL, idx INTEGER NOT NULL, detail TEXT NOT NULL, PRIMARY KEY (job_id, idx));
CREATE INDEX session_experiment ON execution_sessions (job_id);
CREATE TRIGGER jobs_valid_insert BEFORE INSERT ON jobs
                WHEN (NEW.status IN ('DONE', 'FAILED', 'CANCELLED') AND (NEW.finished_at IS NULL OR NEW.running_since IS NOT NULL OR NEW.local = 1 OR NEW.paused = 1)) OR (NEW.status IN ('QUEUED', 'RUNNING') AND NEW.finished_at IS NOT NULL) OR NEW.status NOT IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED') BEGIN SELECT RAISE(ABORT, 'job state is not valid'); END;
CREATE TRIGGER sessions_desired_insert BEFORE INSERT ON execution_sessions
                WHEN NEW.desired NOT IN ('run', 'drain', 'stop') BEGIN SELECT RAISE(ABORT, 'session desired state is not valid'); END;
CREATE TRIGGER jobs_valid_update BEFORE UPDATE ON jobs
                WHEN (NEW.status IN ('DONE', 'FAILED', 'CANCELLED') AND (NEW.finished_at IS NULL OR NEW.running_since IS NOT NULL OR NEW.local = 1 OR NEW.paused = 1)) OR (NEW.status IN ('QUEUED', 'RUNNING') AND NEW.finished_at IS NOT NULL) OR NEW.status NOT IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED') BEGIN SELECT RAISE(ABORT, 'job state is not valid'); END;
CREATE TRIGGER sessions_desired_update BEFORE UPDATE ON execution_sessions
                WHEN NEW.desired NOT IN ('run', 'drain', 'stop') BEGIN SELECT RAISE(ABORT, 'session desired state is not valid'); END;
CREATE TRIGGER jobs_cascade AFTER DELETE ON jobs BEGIN
            DELETE FROM commands WHERE job_id = OLD.id; DELETE FROM arms WHERE job_id = OLD.id; DELETE FROM problems WHERE job_id = OLD.id; DELETE FROM cases WHERE job_id = OLD.id; DELETE FROM holds WHERE job_id = OLD.id; DELETE FROM execution_ingestion WHERE job_id = OLD.id; DELETE FROM execution_checks WHERE job_id = OLD.id; DELETE FROM execution_queue_recipes WHERE job_id = OLD.id; DELETE FROM execution_selections WHERE job_id = OLD.id; DELETE FROM execution_events WHERE job_id = OLD.id; DELETE FROM iteration_attempts WHERE job_id = OLD.id; DELETE FROM execution_outbox WHERE job_id = OLD.id; DELETE FROM execution_sessions WHERE job_id = OLD.id; DELETE FROM trend_points WHERE job_id = OLD.id;
            END;
CREATE TRIGGER jobs_lifecycle BEFORE UPDATE OF status ON jobs
                WHEN NOT ((OLD.status = 'QUEUED' AND NEW.status = 'QUEUED') OR (OLD.status = 'RUNNING' AND NEW.status = 'RUNNING') OR (OLD.status = 'DONE' AND NEW.status = 'DONE') OR (OLD.status = 'FAILED' AND NEW.status = 'FAILED') OR (OLD.status = 'CANCELLED' AND NEW.status = 'CANCELLED') OR (OLD.status = 'QUEUED' AND NEW.status = 'RUNNING') OR (OLD.status = 'QUEUED' AND NEW.status = 'DONE') OR (OLD.status = 'QUEUED' AND NEW.status = 'FAILED') OR (OLD.status = 'QUEUED' AND NEW.status = 'CANCELLED') OR (OLD.status = 'RUNNING' AND NEW.status = 'QUEUED') OR (OLD.status = 'RUNNING' AND NEW.status = 'DONE') OR (OLD.status = 'RUNNING' AND NEW.status = 'FAILED') OR (OLD.status = 'RUNNING' AND NEW.status = 'CANCELLED') OR (OLD.status = 'FAILED' AND NEW.status = 'QUEUED') OR (OLD.status = 'CANCELLED' AND NEW.status = 'QUEUED')) BEGIN SELECT RAISE(ABORT, 'job status move is not allowed'); END;
CREATE INDEX jobs_series ON jobs (series);
CREATE TRIGGER commands_lifecycle BEFORE UPDATE OF status ON commands
                WHEN NOT ((OLD.status = 'QUEUED' AND NEW.status = 'QUEUED') OR (OLD.status = 'RUNNING' AND NEW.status = 'RUNNING') OR (OLD.status = 'DONE' AND NEW.status = 'DONE') OR (OLD.status = 'FAILED' AND NEW.status = 'FAILED') OR (OLD.status = 'CANCELLED' AND NEW.status = 'CANCELLED') OR (OLD.status = 'QUEUED' AND NEW.status = 'RUNNING') OR (OLD.status = 'QUEUED' AND NEW.status = 'DONE') OR (OLD.status = 'QUEUED' AND NEW.status = 'FAILED') OR (OLD.status = 'QUEUED' AND NEW.status = 'CANCELLED') OR (OLD.status = 'RUNNING' AND NEW.status = 'QUEUED') OR (OLD.status = 'RUNNING' AND NEW.status = 'DONE') OR (OLD.status = 'RUNNING' AND NEW.status = 'FAILED') OR (OLD.status = 'RUNNING' AND NEW.status = 'CANCELLED') OR (OLD.status = 'DONE' AND NEW.status = 'QUEUED') OR (OLD.status = 'FAILED' AND NEW.status = 'QUEUED') OR (OLD.status = 'CANCELLED' AND NEW.status = 'QUEUED')) BEGIN SELECT RAISE(ABORT, 'command status move is not allowed'); END;
CREATE INDEX attempt_session ON iteration_attempts (session_id, generation);
CREATE INDEX attempt_job ON iteration_attempts (job_id, state);
CREATE INDEX event_job ON execution_events (job_id, detail);
CREATE TABLE execution_manifests (session_id TEXT PRIMARY KEY, manifest TEXT NOT NULL);
CREATE TRIGGER session_manifest_cascade AFTER DELETE ON execution_sessions BEGIN DELETE FROM execution_manifests WHERE session_id = OLD.id; END;
CREATE TABLE bench_selections (selection_key TEXT PRIMARY KEY, job_id INTEGER NOT NULL, entry TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX bench_selection_job ON bench_selections (job_id);
CREATE TRIGGER bench_selection_cascade AFTER DELETE ON jobs BEGIN DELETE FROM bench_selections WHERE job_id = OLD.id; END;
CREATE INDEX session_live ON execution_sessions (id) WHERE desired != 'stop';
CREATE TABLE check_revisions (job_id INTEGER PRIMARY KEY, revision INTEGER NOT NULL);
CREATE TRIGGER check_revision_update AFTER UPDATE OF lab_check ON cases WHEN OLD.lab_check IS NOT NEW.lab_check BEGIN UPDATE check_revisions SET revision = revision + 1 WHERE job_id = 0;  INSERT INTO check_revisions VALUES (NEW.job_id, 1) ON CONFLICT(job_id) DO UPDATE SET revision = revision + 1;  END;
CREATE TRIGGER check_revision_insert AFTER INSERT ON cases WHEN NEW.lab_check IS NOT NULL BEGIN UPDATE check_revisions SET revision = revision + 1 WHERE job_id = 0;  INSERT INTO check_revisions VALUES (NEW.job_id, 1) ON CONFLICT(job_id) DO UPDATE SET revision = revision + 1;  END;
CREATE TRIGGER check_revision_delete AFTER DELETE ON cases WHEN OLD.lab_check IS NOT NULL BEGIN UPDATE check_revisions SET revision = revision + 1 WHERE job_id = 0;  END;
CREATE INDEX jobs_activity ON jobs (status, paused, cancel_requested);
CREATE TABLE trend_points (job_id INTEGER PRIMARY KEY, version TEXT NOT NULL, reference_stamp INTEGER, computed_at INTEGER NOT NULL, run TEXT, problems TEXT NOT NULL);
CREATE TABLE provenance_parts (sha TEXT PRIMARY KEY, part TEXT NOT NULL);
CREATE TABLE manifest_parts (sha TEXT PRIMARY KEY, part TEXT NOT NULL);
INSERT INTO check_revisions VALUES(0,0);
