-- Add is_blocked column to users table for authorization control
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS is_blocked BOOLEAN DEFAULT FALSE;
