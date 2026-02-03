-- Create public.users table for JWT-based auth
-- User IDs are deterministic UUIDs derived from Google user IDs

CREATE TABLE IF NOT EXISTS public.users (
  id UUID PRIMARY KEY,
  email TEXT,
  name TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Migrate existing user IDs from other tables into users table
-- This ensures FK constraints won't fail on existing data
INSERT INTO public.users (id)
SELECT DISTINCT user_id FROM public.watched_cases WHERE user_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.users (id)
SELECT DISTINCT user_id FROM public.comments WHERE user_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.users (id)
SELECT DISTINCT updated_by FROM public.case_status WHERE updated_by IS NOT NULL
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.users (id)
SELECT DISTINCT user_id FROM public.notifications WHERE user_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.users (id)
SELECT DISTINCT actor_id FROM public.notifications WHERE actor_id IS NOT NULL
ON CONFLICT (id) DO NOTHING;

-- Drop existing foreign key constraints referencing auth.users
ALTER TABLE public.watched_cases DROP CONSTRAINT IF EXISTS watched_cases_user_id_fkey;
ALTER TABLE public.comments DROP CONSTRAINT IF EXISTS comments_user_id_fkey;
ALTER TABLE public.case_status DROP CONSTRAINT IF EXISTS case_status_updated_by_fkey;
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_user_id_fkey;
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_actor_id_fkey;

-- Add new foreign key constraints referencing public.users
ALTER TABLE public.watched_cases
  ADD CONSTRAINT watched_cases_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE public.comments
  ADD CONSTRAINT comments_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE public.case_status
  ADD CONSTRAINT case_status_updated_by_fkey
  FOREIGN KEY (updated_by) REFERENCES public.users(id) ON DELETE SET NULL;

ALTER TABLE public.notifications
  ADD CONSTRAINT notifications_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE public.notifications
  ADD CONSTRAINT notifications_actor_id_fkey
  FOREIGN KEY (actor_id) REFERENCES public.users(id) ON DELETE SET NULL;

-- Create index for faster lookups
CREATE INDEX IF NOT EXISTS idx_users_email ON public.users(email);
