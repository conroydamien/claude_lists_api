-- Comments table. Each row represents a discussion message on an item.
CREATE TABLE comments (
    id          SERIAL PRIMARY KEY,
    item_id     INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    author_id   TEXT,  -- JWT sub claim (user ID from Keycloak)
    author_name TEXT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enable Row Level Security
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
        -- Admin can delete any comment
        current_setting('request.jwt.claims', true)::json->>'role' = 'web_admin'
        OR
        -- Author can delete their own comment
        (author_id IS NOT NULL AND
         author_id = current_setting('request.jwt.claims', true)::json->>'sub')
    );

-- Force RLS for all roles
ALTER TABLE comments FORCE ROW LEVEL SECURITY;

-- Notify function: sends a JSON payload on the 'comments_changed' channel
-- whenever a row in the comments table is inserted, updated, or deleted.
CREATE OR REPLACE FUNCTION notify_comments_change() RETURNS trigger AS $$
DECLARE
    payload JSON;
    rec RECORD;
    parent_list_id INTEGER;
BEGIN
    IF TG_OP = 'DELETE' THEN
        rec := OLD;
    ELSE
        rec := NEW;
    END IF;

    -- Look up the list_id from the parent item
    SELECT list_id INTO parent_list_id FROM items WHERE id = rec.item_id;

    payload := json_build_object(
        'operation', TG_OP,
        'id', rec.id,
        'item_id', rec.item_id,
        'list_id', parent_list_id
    );

    PERFORM pg_notify('comments_changed', payload::text);
    RETURN rec;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER comments_changed
    AFTER INSERT OR UPDATE OR DELETE ON comments
    FOR EACH ROW
    EXECUTE FUNCTION notify_comments_change();
