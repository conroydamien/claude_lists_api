-- DEFINITIVE FIX: Restore working notification system
-- Auto-watch is disabled, notifications work for watched cases/lists

-- Clean up any leftover columns
ALTER TABLE watched_cases DROP COLUMN IF EXISTS opted_out;

-- Disable auto-watch completely (user controls via bell icon)
CREATE OR REPLACE FUNCTION auto_watch_on_comment()
RETURNS TRIGGER AS $$
BEGIN
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Working notification function for comments
CREATE OR REPLACE FUNCTION notify_on_comment()
RETURNS TRIGGER AS $$
DECLARE
    watcher RECORD;
BEGIN
    FOR watcher IN
        SELECT DISTINCT user_id
        FROM watched_cases
        WHERE list_source_url = NEW.list_source_url
        AND case_number = NEW.case_number
        AND user_id != NEW.user_id
    LOOP
        INSERT INTO notifications (
            user_id, type, list_source_url, case_number,
            actor_name, actor_id, content
        ) VALUES (
            watcher.user_id, 'comment', NEW.list_source_url, NEW.case_number,
            NEW.author_name, NEW.user_id, NEW.content
        );
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Working notification function for status changes
CREATE OR REPLACE FUNCTION notify_on_status_change()
RETURNS TRIGGER AS $$
DECLARE
    watcher RECORD;
    notification_type TEXT;
    actor_email TEXT;
BEGIN
    IF OLD.done = NEW.done THEN
        RETURN NEW;
    END IF;

    notification_type := CASE WHEN NEW.done THEN 'status_done' ELSE 'status_undone' END;
    SELECT email INTO actor_email FROM auth.users WHERE id = NEW.updated_by;

    FOR watcher IN
        SELECT DISTINCT user_id
        FROM watched_cases
        WHERE list_source_url = NEW.list_source_url
        AND case_number = NEW.case_number
        AND user_id != NEW.updated_by
    LOOP
        INSERT INTO notifications (
            user_id, type, list_source_url, case_number,
            actor_name, actor_id
        ) VALUES (
            watcher.user_id, notification_type, NEW.list_source_url, NEW.case_number,
            COALESCE(actor_email, 'Someone'), NEW.updated_by
        );
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Ensure triggers exist
DROP TRIGGER IF EXISTS on_comment_insert_auto_watch ON comments;
CREATE TRIGGER on_comment_insert_auto_watch
    AFTER INSERT ON comments
    FOR EACH ROW
    EXECUTE FUNCTION auto_watch_on_comment();

DROP TRIGGER IF EXISTS on_comment_insert_notify ON comments;
CREATE TRIGGER on_comment_insert_notify
    AFTER INSERT ON comments
    FOR EACH ROW
    EXECUTE FUNCTION notify_on_comment();

DROP TRIGGER IF EXISTS on_status_change_notify ON case_status;
CREATE TRIGGER on_status_change_notify
    AFTER UPDATE ON case_status
    FOR EACH ROW
    EXECUTE FUNCTION notify_on_status_change();
