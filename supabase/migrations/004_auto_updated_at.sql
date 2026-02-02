-- Auto-update the updated_at timestamp on case_status changes
-- This ensures all timestamps come from the server, avoiding clock drift between devices

CREATE OR REPLACE FUNCTION update_case_status_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER case_status_updated_at
    BEFORE INSERT OR UPDATE ON case_status
    FOR EACH ROW
    EXECUTE FUNCTION update_case_status_timestamp();
