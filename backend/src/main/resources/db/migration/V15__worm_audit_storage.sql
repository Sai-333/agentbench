-- WORM / append-only audit storage (local Postgres; no AWS Object Lock required).
-- Hardens existing evaluation_state_transitions + adds hash-chained platform.worm_audit_events.
-- Dual-write: AFTER INSERT on evaluation_state_transitions → worm_audit_events.
-- Residual: not S3 Object Lock / Compliance mode; Flyway superuser can DROP TRIGGER / TRUNCATE.

-- ---------------------------------------------------------------------------
-- Shared deny-mutation trigger (fires for all roles, including table owner).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform.worm_deny_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'WORM audit table %.% is append-only: % is forbidden',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

COMMENT ON FUNCTION platform.worm_deny_mutation() IS
    'BEFORE UPDATE OR DELETE guard for append-only audit tables.';

-- Harden existing evaluation state audit trail
CREATE TRIGGER evaluation_state_transitions_worm_deny
    BEFORE UPDATE OR DELETE ON evaluations.evaluation_state_transitions
    FOR EACH ROW
    EXECUTE FUNCTION platform.worm_deny_mutation();

REVOKE UPDATE, DELETE ON evaluations.evaluation_state_transitions FROM agentbench_app;
GRANT SELECT, INSERT ON evaluations.evaluation_state_transitions TO agentbench_app;

-- ---------------------------------------------------------------------------
-- Hash-chained WORM audit events (per-org chain tip via advisory lock)
-- ---------------------------------------------------------------------------
CREATE TABLE platform.worm_audit_events (
    id              BIGSERIAL PRIMARY KEY,
    org_id          UUID REFERENCES identity.organizations (id),
    event_type      TEXT NOT NULL,
    event_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor           TEXT NOT NULL DEFAULT 'system',
    resource_type   TEXT NOT NULL DEFAULT '',
    resource_id     TEXT NOT NULL DEFAULT '',
    payload_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    prev_hash       TEXT NOT NULL,
    entry_hash      TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT worm_audit_events_prev_hash_hex CHECK (prev_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT worm_audit_events_entry_hash_hex CHECK (entry_hash ~ '^[0-9a-f]{64}$')
);

COMMENT ON TABLE platform.worm_audit_events IS
    'Append-only hash-chained audit log. App role: SELECT+INSERT only; UPDATE/DELETE denied by trigger + privileges.';

CREATE INDEX worm_audit_events_org_created_idx
    ON platform.worm_audit_events (org_id, id ASC);
CREATE INDEX worm_audit_events_type_idx
    ON platform.worm_audit_events (event_type, created_at DESC);
CREATE INDEX worm_audit_events_resource_idx
    ON platform.worm_audit_events (resource_type, resource_id);

CREATE OR REPLACE FUNCTION platform.worm_audit_canonical(rec platform.worm_audit_events)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT concat_ws('|',
        rec.id::text,
        COALESCE(rec.org_id::text, ''),
        rec.event_type,
        to_char(rec.event_time AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        rec.actor,
        rec.resource_type,
        rec.resource_id,
        rec.payload_json::text,
        rec.prev_hash
    );
$$;

CREATE OR REPLACE FUNCTION platform.worm_audit_hash(canonical TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT encode(digest(canonical, 'sha256'), 'hex');
$$;

CREATE OR REPLACE FUNCTION platform.worm_audit_before_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    tip_hash TEXT;
    lock_key INT;
BEGIN
    -- Serialize chain tip per org (NULL org → global chain key 0).
    lock_key := hashtext(COALESCE(NEW.org_id::text, ''));
    PERFORM pg_advisory_xact_lock(87201401, lock_key);

    SELECT e.entry_hash
      INTO tip_hash
      FROM platform.worm_audit_events e
     WHERE e.org_id IS NOT DISTINCT FROM NEW.org_id
     ORDER BY e.id DESC
     LIMIT 1;

    NEW.prev_hash := COALESCE(tip_hash, repeat('0', 64));
    NEW.entry_hash := platform.worm_audit_hash(platform.worm_audit_canonical(NEW));
    RETURN NEW;
END;
$$;

CREATE TRIGGER worm_audit_events_before_insert
    BEFORE INSERT ON platform.worm_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION platform.worm_audit_before_insert();

CREATE TRIGGER worm_audit_events_worm_deny
    BEFORE UPDATE OR DELETE ON platform.worm_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION platform.worm_deny_mutation();

-- App role: insert/select only (override V12 default ALL grants)
GRANT SELECT, INSERT ON platform.worm_audit_events TO agentbench_app;
REVOKE UPDATE, DELETE ON platform.worm_audit_events FROM agentbench_app;
GRANT USAGE, SELECT ON SEQUENCE platform.worm_audit_events_id_seq TO agentbench_app;

-- Tenant RLS (NULL org_id rows visible only with bypass — platform-global events)
ALTER TABLE platform.worm_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.worm_audit_events FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation ON platform.worm_audit_events;
CREATE POLICY tenant_isolation ON platform.worm_audit_events
    FOR ALL
    USING (
        app.rls_bypass()
        OR (org_id IS NOT NULL AND app.tenant_ok(org_id))
    )
    WITH CHECK (
        app.rls_bypass()
        OR (org_id IS NOT NULL AND app.tenant_ok(org_id))
    );

-- ---------------------------------------------------------------------------
-- Dual-write: evaluation state transitions → worm_audit_events
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION platform.worm_dual_write_evaluation_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO platform.worm_audit_events (
        org_id, event_type, event_time, actor, resource_type, resource_id, payload_json
    ) VALUES (
        NEW.org_id,
        'evaluation.state_transition',
        NEW.created_at,
        NEW.actor,
        'evaluation',
        NEW.evaluation_id::text,
        jsonb_build_object(
            'transition_id', NEW.id,
            'from_state', NEW.from_state,
            'to_state', NEW.to_state,
            'reason', NEW.reason,
            'attempt', NEW.attempt
        )
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER evaluation_state_transitions_worm_dual_write
    AFTER INSERT ON evaluations.evaluation_state_transitions
    FOR EACH ROW
    EXECUTE FUNCTION platform.worm_dual_write_evaluation_transition();

-- Backfill existing transitions into the hash chain (Flyway superuser; deterministic order).
INSERT INTO platform.worm_audit_events (
    org_id, event_type, event_time, actor, resource_type, resource_id, payload_json
)
SELECT
    t.org_id,
    'evaluation.state_transition',
    t.created_at,
    t.actor,
    'evaluation',
    t.evaluation_id::text,
    jsonb_build_object(
        'transition_id', t.id,
        'from_state', t.from_state,
        'to_state', t.to_state,
        'reason', t.reason,
        'attempt', t.attempt,
        'backfill', true
    )
FROM evaluations.evaluation_state_transitions t
ORDER BY t.org_id NULLS FIRST, t.id ASC;

INSERT INTO platform.schema_marker (migration_label)
VALUES ('worm_audit_storage');
