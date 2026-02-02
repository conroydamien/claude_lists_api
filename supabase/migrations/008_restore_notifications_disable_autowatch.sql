-- Restore original notification functions exactly as they were
-- Disable auto-watch (user manually controls watch via bell icon)

-- Drop opted_out column if it exists from previous failed migration
ALTER TABLE watched_cases DROP COLUMN IF EXISTS opted_out;

-- Disable auto-watch - just return without doing anything
CREATE OR REPLACE FUNCTION auto_watch_on_comment()
RETURNS TRIGGER AS $$
BEGIN
    -- Auto-watch disabled - user manually controls via bell icon
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Restore exact original notify_on_comment function
CREATE OR REPLACE FUNCTION notify_on_comment()
RETURNS TRIGGER AS $$
DECLARE
    watcher RECORD;
BEGIN
    -- Create notification for each watcher (except the commenter)
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

-- Restore exact original notify_on_status_change function
CREATE OR REPLACE FUNCTION notify_on_status_change()
RETURNS TRIGGER AS $$
DECLARE
    watcher RECORD;
    notification_type TEXT;
    actor_email TEXT;
BEGIN
    -- Only notify if done status actually changed
    IF OLD.done = NEW.done THEN
        RETURN NEW;
    END IF;

    -- Determine notification type
    notification_type := CASE WHEN NEW.done THEN 'status_done' ELSE 'status_undone' END;

    -- Get actor email
    SELECT email INTO actor_email FROM auth.users WHERE id = NEW.updated_by;

    -- Create notification for each watcher (except the actor)
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
