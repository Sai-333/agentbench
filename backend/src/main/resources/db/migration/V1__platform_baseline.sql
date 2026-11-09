-- Milestone 1 baseline: establish PostgreSQL extensions and schema conventions
-- from DATABASE_DESIGN without creating product entity tables yet (those arrive M2+).
-- Single Flyway stream; forward-only; never edit applied migrations.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "citext";

-- Logical schema boundary for shared platform / infrastructure tables.
CREATE SCHEMA IF NOT EXISTS platform;

COMMENT ON SCHEMA platform IS 'Shared platform kernel objects (outbox, health markers). Product schemas follow in later milestones.';

-- Tiny placeholder used to verify Flyway + JDBC connectivity in M1.
-- Not a product entity; safe to retain as a deployment/schema marker.
CREATE TABLE platform.schema_marker (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    migration_label TEXT NOT NULL,
    applied_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO platform.schema_marker (migration_label)
VALUES ('M1_baseline');

-- Convention notes (enforced in later migrations):
-- * Tenant tables carry org_id UUID
-- * Public IDs are UUID
-- * Timestamps are TIMESTAMPTZ
-- * Email/slug columns prefer CITEXT
