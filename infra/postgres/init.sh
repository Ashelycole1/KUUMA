#!/bin/sh
# One schema per build, each owned by its own role (rule 2: own your data).
# A service connects as its role and can touch only its own schema.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON DATABASE "$POSTGRES_DB" FROM PUBLIC;

CREATE ROLE ai_svc        LOGIN PASSWORD '${AI_DB_PASSWORD}';
CREATE ROLE companion_svc LOGIN PASSWORD '${COMPANION_DB_PASSWORD}';
CREATE ROLE casefile_svc  LOGIN PASSWORD '${CASEFILE_DB_PASSWORD}';
CREATE ROLE guardian_svc  LOGIN PASSWORD '${GUARDIAN_DB_PASSWORD}';

GRANT CONNECT ON DATABASE "$POSTGRES_DB" TO ai_svc, companion_svc, casefile_svc, guardian_svc;

CREATE SCHEMA ai        AUTHORIZATION ai_svc;
CREATE SCHEMA companion AUTHORIZATION companion_svc;
CREATE SCHEMA casefile  AUTHORIZATION casefile_svc;
CREATE SCHEMA guardian  AUTHORIZATION guardian_svc;

ALTER ROLE ai_svc        SET search_path = ai;
ALTER ROLE companion_svc SET search_path = companion;
ALTER ROLE casefile_svc  SET search_path = casefile;
ALTER ROLE guardian_svc  SET search_path = guardian;
SQL
