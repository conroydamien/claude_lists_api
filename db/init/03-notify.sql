-- Notify function: sends a JSON payload on the 'items_changed' channel
-- whenever a row in the items table is inserted, updated, or deleted.
CREATE OR REPLACE FUNCTION notify_items_change() RETURNS trigger AS $$
DECLARE
    payload JSON;
    rec RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        rec := OLD;
    ELSE
        rec := NEW;
    END IF;

    payload := json_build_object(
        'operation', TG_OP,
        'id', rec.id,
        'list_id', rec.list_id
    );

    PERFORM pg_notify('items_changed', payload::text);
    RETURN rec;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER items_changed
    AFTER INSERT OR UPDATE OR DELETE ON items
    FOR EACH ROW
    EXECUTE FUNCTION notify_items_change();
