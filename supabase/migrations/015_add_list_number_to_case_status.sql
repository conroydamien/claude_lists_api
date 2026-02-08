-- Add list_number to case_status and include it in the primary key
-- so the same case at different list positions can be tracked separately.
-- Default to 0 for backward compatibility with older app versions.

ALTER TABLE public.case_status ADD COLUMN IF NOT EXISTS list_number INTEGER NOT NULL DEFAULT 0;

-- Replace the primary key to include list_number
ALTER TABLE public.case_status DROP CONSTRAINT case_status_pkey;
ALTER TABLE public.case_status ADD PRIMARY KEY (list_source_url, case_number, list_number);
