-- Comments table. Each row represents a discussion message on an item.
CREATE TABLE comments (
    id          SERIAL PRIMARY KEY,
    item_id     INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    author_name TEXT NOT NULL,
    content     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
