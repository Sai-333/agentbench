-- Runs before Flyway in Testcontainers so the app pool can open as agentbench_app.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentbench_app') THEN
        CREATE ROLE agentbench_app LOGIN PASSWORD 'agentbench' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;
