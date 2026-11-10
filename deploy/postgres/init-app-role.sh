#!/bin/bash
# Creates the non-superuser application role so FORCE RLS is effective.
# Mounted into the Compose Postgres image at docker-entrypoint-initdb.d (first boot only).
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<EOSQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentbench_app') THEN
        CREATE ROLE agentbench_app LOGIN PASSWORD '${POSTGRES_PASSWORD}' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
    END IF;
END
\$\$;
EOSQL
