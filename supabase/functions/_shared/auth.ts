/**
 * Authentication helper for validating Google ID tokens.
 * Used by API endpoints that require authentication.
 */

export interface AuthUser {
  id: string;      // UUID derived from Google user ID
  googleId: string; // Original Google user ID (sub claim)
  email?: string;
  name?: string;
}

/**
 * Convert Google ID to a deterministic UUID.
 * Uses SHA-256 hash formatted as UUID v4 format.
 */
async function googleIdToUuid(googleId: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(`google:${googleId}`);
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  const hashArray = new Uint8Array(hashBuffer);

  // Format as UUID (take first 16 bytes)
  const hex = Array.from(hashArray.slice(0, 16))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');

  // Format: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
  // Set version (4) and variant bits
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-${((parseInt(hex.slice(16, 18), 16) & 0x3f) | 0x80).toString(16).padStart(2, '0')}${hex.slice(18, 20)}-${hex.slice(20, 32)}`;
}

/**
 * Validate a Google ID token and extract user info.
 * Returns null if token is invalid.
 */
export async function validateGoogleToken(token: string): Promise<AuthUser | null> {
  try {
    // Validate token with Google's tokeninfo endpoint
    const response = await fetch(
      `https://oauth2.googleapis.com/tokeninfo?id_token=${token}`
    );

    if (!response.ok) {
      console.error('Token validation failed:', response.status);
      return null;
    }

    const payload = await response.json();

    // Verify the token is for our app (check audience)
    // The aud should match one of our client IDs
    const validAudiences = [
      '807765424446-baktcv20bbq38s3t4u7j744cl558qbot.apps.googleusercontent.com', // iOS
      '807765424446-ofrgb5gs9l0lo8usfj37258iuj93cfdd.apps.googleusercontent.com', // Web/Android
    ];

    if (!validAudiences.includes(payload.aud)) {
      console.error('Invalid audience:', payload.aud);
      return null;
    }

    // Convert Google ID to UUID for database compatibility
    const uuid = await googleIdToUuid(payload.sub);

    return {
      id: uuid,
      googleId: payload.sub,
      email: payload.email,
      name: payload.name,
    };
  } catch (error) {
    console.error('Token validation error:', error);
    return null;
  }
}

/**
 * Extract bearer token from Authorization header.
 */
export function extractBearerToken(authHeader: string | null): string | null {
  if (!authHeader) return null;
  const parts = authHeader.split(' ');
  if (parts.length !== 2 || parts[0].toLowerCase() !== 'bearer') return null;
  return parts[1];
}

/**
 * Standard CORS headers for API responses.
 */
export const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type, apikey',
};

/**
 * Create a JSON response with CORS headers.
 */
export function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json',
      ...corsHeaders,
    },
  });
}

/**
 * Create an error response.
 */
export function errorResponse(message: string, status = 400): Response {
  return jsonResponse({ error: message }, status);
}

/**
 * Handle CORS preflight request.
 */
export function handleCors(): Response {
  return new Response('ok', { headers: corsHeaders });
}

/**
 * Ensure user exists in public.users table.
 * Call this after validating the token to maintain user records.
 */
export async function ensureUser(db: any, user: AuthUser): Promise<void> {
  const { error } = await db.from('users').upsert(
    {
      id: user.id,
      email: user.email,
      name: user.name,
      updated_at: new Date().toISOString(),
    },
    { onConflict: 'id' }
  );

  if (error) {
    console.error('Failed to upsert user:', error);
    // Don't throw - user record is nice to have but not critical for the request
  }
}
