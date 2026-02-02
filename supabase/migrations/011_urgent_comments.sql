-- Add urgent flag to comments
ALTER TABLE comments ADD COLUMN IF NOT EXISTS urgent BOOLEAN DEFAULT FALSE;

-- Index for quick lookup of urgent comments per case
CREATE INDEX IF NOT EXISTS idx_comments_urgent
ON comments(list_source_url, case_number)
WHERE urgent = TRUE;
