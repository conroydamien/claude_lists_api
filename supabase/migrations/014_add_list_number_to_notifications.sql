-- Add list_number to notifications so clients can show the case's position in the list
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS list_number INTEGER;
