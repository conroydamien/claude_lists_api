/**
 * API endpoint for watched cases.
 *
 * GET /watched-cases - Get user's watched cases
 * POST /watched-cases - Watch a case
 * DELETE /watched-cases - Unwatch a case
 *
 * All operations require authentication via Google ID token.
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';
import {
  validateGoogleToken,
  extractBearerToken,
  jsonResponse,
  errorResponse,
  handleCors,
  ensureUser,
} from '../_shared/auth.ts';
import { getDbClient } from '../_shared/db.ts';

interface WatchRequest {
  list_source_url: string;
  case_number: string;
  source?: string;
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return handleCors();
  }

  // Validate authentication
  const token = extractBearerToken(req.headers.get('Authorization'));
  if (!token) {
    return errorResponse('Missing authorization token', 401);
  }

  const user = await validateGoogleToken(token);
  if (!user) {
    return errorResponse('Invalid or expired token', 401);
  }

  const db = getDbClient();

  // Ensure user exists in database and check if blocked
  const blocked = await ensureUser(db, user);
  if (blocked) {
    return errorResponse('Account is blocked', 403);
  }

  try {
    switch (req.method) {
      case 'GET': {
        // Get user's watched cases
        const { data, error } = await db
          .from('watched_cases')
          .select('*')
          .eq('user_id', user.id);

        if (error) throw error;
        return jsonResponse(data);
      }

      case 'POST': {
        // Watch a case
        const body: WatchRequest = await req.json();

        if (!body.list_source_url || !body.case_number) {
          return errorResponse('list_source_url and case_number are required');
        }

        const { error } = await db.from('watched_cases').insert({
          user_id: user.id,
          list_source_url: body.list_source_url,
          case_number: body.case_number,
          source: body.source || 'manual',
        });

        if (error) {
          // Handle duplicate (already watching)
          if (error.code === '23505') {
            return jsonResponse({ message: 'Already watching' });
          }
          throw error;
        }

        return jsonResponse({ message: 'Watching case' }, 201);
      }

      case 'DELETE': {
        // Unwatch a case
        const body: WatchRequest = await req.json();

        if (!body.list_source_url || !body.case_number) {
          return errorResponse('list_source_url and case_number are required');
        }

        const { error } = await db
          .from('watched_cases')
          .delete()
          .eq('user_id', user.id)
          .eq('list_source_url', body.list_source_url)
          .eq('case_number', body.case_number);

        if (error) throw error;
        return jsonResponse({ message: 'Unwatched case' });
      }

      default:
        return errorResponse('Method not allowed', 405);
    }
  } catch (error: any) {
    console.error('Database error:', error);
    const message = error?.message || error?.details || error?.hint || JSON.stringify(error);
    return errorResponse(`Database error: ${message}`, 500);
  }
});
