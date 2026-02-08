-- Add list_number to comments for per-position tracking
ALTER TABLE public.comments ADD COLUMN IF NOT EXISTS list_number INTEGER NOT NULL DEFAULT 0;
