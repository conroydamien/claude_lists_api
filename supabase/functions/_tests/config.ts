/**
 * Test configuration for backend tests.
 *
 * For integration tests, set GOOGLE_ID_TOKEN environment variable:
 *   export GOOGLE_ID_TOKEN="your-token-here"
 *
 * Get a token by signing into the app and extracting from logs/network inspector.
 */

export const BASE_URL = Deno.env.get("SUPABASE_FUNCTIONS_URL") ||
  "https://fbiissfiqgtlenxkjuwv.supabase.co/functions/v1";

export const GOOGLE_ID_TOKEN = Deno.env.get("GOOGLE_ID_TOKEN") || "";

export function getAuthHeaders(): Record<string, string> {
  if (!GOOGLE_ID_TOKEN) {
    throw new Error("GOOGLE_ID_TOKEN environment variable required for integration tests");
  }
  return {
    "Authorization": `Bearer ${GOOGLE_ID_TOKEN}`,
    "Content-Type": "application/json",
  };
}

export function skipIfNoToken(): boolean {
  if (!GOOGLE_ID_TOKEN) {
    console.log("⚠️  Skipping integration test - GOOGLE_ID_TOKEN not set");
    return true;
  }
  return false;
}
