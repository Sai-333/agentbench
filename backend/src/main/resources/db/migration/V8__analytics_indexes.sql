-- Milestone 8: analytics query indexes (no materialized metric tables).
-- Prefer aggregating existing evaluations / scores / invocations.
-- Single Flyway stream; forward-only; do not edit V1–V7.

CREATE INDEX IF NOT EXISTS evaluations_org_created_idx
    ON evaluations.evaluations (org_id, created_at DESC);

CREATE INDEX IF NOT EXISTS evaluations_org_state_created_idx
    ON evaluations.evaluations (org_id, state, created_at DESC);

CREATE INDEX IF NOT EXISTS evaluations_org_version_agent_idx
    ON evaluations.evaluations (org_id, benchmark_version_id, agent_id, created_at DESC);

CREATE INDEX IF NOT EXISTS evaluation_scores_org_total_idx
    ON evaluations.evaluation_scores (org_id, weighted_total DESC NULLS LAST);

CREATE INDEX IF NOT EXISTS agent_invocations_org_created_idx
    ON agents.agent_invocations (org_id, created_at DESC);

INSERT INTO platform.schema_marker (migration_label)
VALUES ('M8_analytics_indexes');
