-- TEST MODE: Include self in notifications for testing
-- Remove this migration when testing is complete

-- Notify on comment - includes commenter for testing
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
        -- TEST MODE: removed "AND user_id != NEW.user_id" to include self
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

-- Notify on status change - includes actor for testing
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
        -- TEST MODE: removed "AND user_id != NEW.updated_by" to include self
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
