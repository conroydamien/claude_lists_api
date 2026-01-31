-- Simplified schema for hybrid mode
-- Cases are fetched from courts.ie, only comments and done status are stored locally

-- Case status table: tracks done status by natural keys
CREATE TABLE case_status (
    list_source_url TEXT NOT NULL,
    case_number TEXT NOT NULL,
    done BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (list_source_url, case_number)
);

-- Comments table: references cases by natural keys
CREATE TABLE comments (
    id SERIAL PRIMARY KEY,
    list_source_url TEXT NOT NULL,
    case_number TEXT NOT NULL,
    author_id TEXT,  -- JWT sub claim (user ID from Keycloak)
    author_name TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for efficient comment lookups by case
CREATE INDEX comments_case_idx ON comments (list_source_url, case_number);

-- Enable Row Level Security on comments
ALTER TABLE comments ENABLE ROW LEVEL SECURITY;

-- Authenticated users can read comments
CREATE POLICY comments_select ON comments
    FOR SELECT USING (true);

-- Authenticated users can insert comments
CREATE POLICY comments_insert ON comments
    FOR INSERT WITH CHECK (true);

-- Users can delete their own comments, admins can delete any comment
CREATE POLICY comments_delete ON comments
    FOR DELETE USING (
        current_setting('request.jwt.claims', true)::json->>'role' = 'web_admin'
        OR
        (author_id IS NOT NULL AND
         author_id = current_setting('request.jwt.claims', true)::json->>'sub')
    );

-- Force RLS for all roles
ALTER TABLE comments FORCE ROW LEVEL SECURITY;

-- Enable Row Level Security on case_status
ALTER TABLE case_status ENABLE ROW LEVEL SECURITY;

-- Authenticated users can read/write case status
CREATE POLICY case_status_select ON case_status FOR SELECT USING (true);
CREATE POLICY case_status_insert ON case_status FOR INSERT WITH CHECK (true);
CREATE POLICY case_status_update ON case_status FOR UPDATE USING (true);

ALTER TABLE case_status FORCE ROW LEVEL SECURITY;
