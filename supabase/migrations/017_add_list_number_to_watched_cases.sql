-- Add list_number to watched_cases for per-position tracking
ALTER TABLE public.watched_cases ADD COLUMN IF NOT EXISTS list_number INTEGER NOT NULL DEFAULT 0;

-- Replace the unique constraint to include list_number
ALTER TABLE public.watched_cases DROP CONSTRAINT IF EXISTS watched_cases_user_id_list_source_url_case_number_key;
ALTER TABLE public.watched_cases ADD CONSTRAINT watched_cases_user_id_list_source_url_case_number_list_number_key
  UNIQUE (user_id, list_source_url, case_number, list_number);
