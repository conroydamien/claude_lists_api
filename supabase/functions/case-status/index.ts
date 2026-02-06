/**
 * API endpoint for case status (done/not done).
 * All endpoints require JWT authentication.
 *
 * GET /case-status?list_source_url=X&case_numbers=A,B,C - Get statuses
 * POST /case-status - Upsert status
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

interface UpsertStatusRequest {
  list_source_url: string;
  case_number: string;
  done: boolean;
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return handleCors();
  }

  // Validate JWT for all requests
  const token = extractBearerToken(req.headers.get('Authorization'));
  if (!token) {
    return errorResponse('Authorization required', 401);
  }

  const user = await validateGoogleToken(token);
  if (!user) {
    return errorResponse('Invalid token', 401);
  }

  const db = getDbClient();
  const url = new URL(req.url);

  // Ensure user exists in database
  await ensureUser(db, user);

  try {
    switch (req.method) {
      case 'GET': {
        const listSourceUrl = url.searchParams.get('list_source_url');
        const caseNumbers = url.searchParams.get('case_numbers');

        if (!listSourceUrl || !caseNumbers) {
          return errorResponse('list_source_url and case_numbers are required');
        }

        const numbers = caseNumbers.split(',');
        const { data, error } = await db
          .from('case_status')
          .select('*')
          .eq('list_source_url', listSourceUrl)
          .in('case_number', numbers);

        if (error) throw error;
        return jsonResponse(data);
      }

      case 'POST': {
        const body: UpsertStatusRequest = await req.json();

        if (!body.list_source_url || !body.case_number || body.done === undefined) {
          return errorResponse('list_source_url, case_number, and done are required');
        }

        const { error } = await db.from('case_status').upsert(
          {
            list_source_url: body.list_source_url,
            case_number: body.case_number,
            done: body.done,
            updated_by: user.id,
            updated_at: new Date().toISOString(),
          },
          {
            onConflict: 'list_source_url,case_number',
          }
        );

        if (error) throw error;

        // Create notifications for watchers in background (don't block response)
        (async () => {
          try {
            const { data: watchers } = await db
              .from('watched_cases')
              .select('user_id')
              .eq('list_source_url', body.list_source_url)
              .eq('case_number', body.case_number)
              .neq('user_id', user.id);

            if (watchers && watchers.length > 0) {
              const notificationType = body.done ? 'status_done' : 'status_undone';
              const notifications = watchers.map((w: { user_id: string }) => ({
                user_id: w.user_id,
                type: notificationType,
                list_source_url: body.list_source_url,
                case_number: body.case_number,
                actor_id: user.id,
                actor_name: user.name || user.email || 'Someone',
              }));

              await db.from('notifications').insert(notifications);
            }
          } catch (e) {
            console.error('Failed to create notifications:', e);
          }
        })();

        return jsonResponse({ message: 'Status updated' });
      }

      default:
        return errorResponse('Method not allowed', 405);
    }
  } catch (error) {
    console.error('Database error:', error);
    return errorResponse('Internal server error', 500);
  }
});
