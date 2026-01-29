-- Role used by PostgREST for unauthenticated API requests.
-- We keep web_anon but grant no table access - login is required.
CREATE ROLE web_anon NOLOGIN;

-- Role used by PostgREST for authenticated API requests (via JWT).
CREATE ROLE web_user NOLOGIN;

-- Admin role - can delete any comment
CREATE ROLE web_admin NOLOGIN;

-- Allow roles to see objects in the public schema.
GRANT USAGE ON SCHEMA public TO web_anon, web_user, web_admin;

-- Grant CRUD to authenticated users only (not web_anon).
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO web_user, web_admin;

-- Allow authenticated roles to use sequences (needed for SERIAL/auto-increment columns).
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO web_user, web_admin;
