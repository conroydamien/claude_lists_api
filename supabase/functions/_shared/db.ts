/**
 * Database client for API endpoints.
 * Uses Supabase service role to bypass RLS.
 */

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

// Create Supabase client with service role (bypasses RLS)
export function getDbClient() {
  const supabaseUrl = Deno.env.get('SUPABASE_URL')!;
  const supabaseServiceKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;

  return createClient(supabaseUrl, supabaseServiceKey);
}
