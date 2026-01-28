-- Role used by PostgREST for unauthenticated API requests.
-- NOLOGIN means it cannot connect directly; PostgREST switches to it internally.
CREATE ROLE web_anon NOLOGIN;

-- Role used by PostgREST for authenticated API requests (via JWT).
CREATE ROLE web_user NOLOGIN;

-- Allow both roles to see objects in the public schema.
GRANT USAGE ON SCHEMA public TO web_anon, web_user;

-- Automatically grant full CRUD on any tables created in public.
-- This applies to tables created *after* this statement runs (e.g. 01-schema.sql).
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO web_anon, web_user;

-- Allow both roles to use sequences (needed for SERIAL/auto-increment columns).
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO web_anon, web_user;
