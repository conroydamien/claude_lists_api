-- Simplify RLS policies for Supabase Auth via signInWithIdToken
--
-- Architecture:
-- - Native Google Sign-In on mobile apps
-- - Google ID token exchanged with Supabase for session (signInWithIdToken)
-- - Supabase session used for Realtime subscriptions (RLS applies)
-- - Google ID token used for Edge Function API calls (they validate & authorize)
--
-- Note: user_id in tables is a derived UUID from Google ID, NOT the Supabase auth.uid().
-- So RLS cannot check auth.uid() = user_id. Instead:
-- - SELECT: Allow for any authenticated user (Realtime needs this)
-- - INSERT/UPDATE/DELETE: Edge Functions handle authorization, RLS just checks auth

-- Drop ALL existing policies on these tables to start fresh
DO $$
DECLARE
    pol RECORD;
BEGIN
    FOR pol IN
        SELECT policyname, tablename
        FROM pg_policies
        WHERE schemaname = 'public'
        AND tablename IN ('comments', 'case_status', 'watched_cases', 'notifications')
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON %I', pol.policyname, pol.tablename);
    END LOOP;
END $$;

-- Now safe to drop the is_approved function
DROP FUNCTION IF EXISTS is_approved();

-- Comments: authenticated users can read all, write handled by Edge Functions
CREATE POLICY "Authenticated users can read comments"
    ON comments FOR SELECT
    USING (auth.uid() IS NOT NULL);

CREATE POLICY "Authenticated users can insert comments"
    ON comments FOR INSERT
    WITH CHECK (auth.uid() IS NOT NULL);

CREATE POLICY "Authenticated users can delete comments"
    ON comments FOR DELETE
    USING (auth.uid() IS NOT NULL);

-- Case status: authenticated users can read all, write handled by Edge Functions
CREATE POLICY "Authenticated users can read case_status"
    ON case_status FOR SELECT
    USING (auth.uid() IS NOT NULL);

CREATE POLICY "Authenticated users can insert case_status"
    ON case_status FOR INSERT
    WITH CHECK (auth.uid() IS NOT NULL);

CREATE POLICY "Authenticated users can update case_status"
    ON case_status FOR UPDATE
    USING (auth.uid() IS NOT NULL);

-- Watched cases: authenticated users can read/write, Edge Functions validate ownership
CREATE POLICY "Authenticated users can read watched_cases"
    ON watched_cases FOR SELECT
    USING (auth.uid() IS NOT NULL);

CREATE POLICY "Authenticated users can insert watched_cases"
    ON watched_cases FOR INSERT
    WITH CHECK (auth.uid() IS NOT NULL);

CREATE POLICY "Authenticated users can delete watched_cases"
    ON watched_cases FOR DELETE
    USING (auth.uid() IS NOT NULL);

-- Notifications: authenticated users can read all (client filters by user_id)
-- This is needed for Realtime subscriptions to work
CREATE POLICY "Authenticated users can read notifications"
    ON notifications FOR SELECT
    USING (auth.uid() IS NOT NULL);

CREATE POLICY "Authenticated users can update notifications"
    ON notifications FOR UPDATE
    USING (auth.uid() IS NOT NULL);

CREATE POLICY "Authenticated users can delete notifications"
    ON notifications FOR DELETE
    USING (auth.uid() IS NOT NULL);
