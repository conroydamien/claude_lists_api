/**
 * API endpoint for comments.
 * All endpoints require JWT authentication.
 *
 * GET /comments?list_source_url=X&case_number=Y - Get comments for a case
 * GET /comments?list_source_url=X&case_numbers=A,B,C - Get comment counts
 * POST /comments - Add a comment
 * DELETE /comments - Delete a comment
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

interface AddCommentRequest {
  list_source_url: string;
  case_number: string;
  content: string;
  urgent?: boolean;
}

interface DeleteCommentRequest {
  id: number;
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return handleCors();
  }

  const db = getDbClient();
  const url = new URL(req.url);

  // Validate JWT for all requests
  const token = extractBearerToken(req.headers.get('Authorization'));
  if (!token) {
    return errorResponse('Authorization required', 401);
  }

  const user = await validateGoogleToken(token);
  if (!user) {
    return errorResponse('Invalid token', 401);
  }

  // Ensure user exists in database
  await ensureUser(db, user);

  try {
    switch (req.method) {
      case 'GET': {
        const listSourceUrl = url.searchParams.get('list_source_url');
        const caseNumber = url.searchParams.get('case_number');
        const caseNumbers = url.searchParams.get('case_numbers'); // comma-separated for counts

        if (!listSourceUrl) {
          return errorResponse('list_source_url is required');
        }

        if (caseNumbers) {
          // Get comment counts for multiple cases
          const numbers = caseNumbers.split(',');
          const { data, error } = await db
            .from('comments')
            .select('case_number, urgent')
            .eq('list_source_url', listSourceUrl)
            .in('case_number', numbers);

          if (error) throw error;
          return jsonResponse(data);
        } else if (caseNumber) {
          // Get comments for a specific case
          const { data, error } = await db
            .from('comments')
            .select('*')
            .eq('list_source_url', listSourceUrl)
            .eq('case_number', caseNumber)
            .order('created_at', { ascending: true });

          if (error) throw error;
          return jsonResponse(data);
        } else {
          return errorResponse('case_number or case_numbers is required');
        }
      }

      case 'POST': {
        const body: AddCommentRequest = await req.json();

        if (!body.list_source_url || !body.case_number || !body.content) {
          return errorResponse('list_source_url, case_number, and content are required');
        }

        const authorName = user.name || user.email || 'Anonymous';

        const { error } = await db.from('comments').insert({
          list_source_url: body.list_source_url,
          case_number: body.case_number,
          user_id: user.id,
          author_name: authorName,
          content: body.content,
          urgent: body.urgent || false,
        });

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
              const notifications = watchers.map((w: { user_id: string }) => ({
                user_id: w.user_id,
                type: 'comment',
                list_source_url: body.list_source_url,
                case_number: body.case_number,
                actor_id: user.id,
                actor_name: authorName,
                content: body.content.substring(0, 100),
              }));

              await db.from('notifications').insert(notifications);
            }
          } catch (e) {
            console.error('Failed to create notifications:', e);
          }
        })();

        return jsonResponse({ message: 'Comment added' }, 201);
      }

      case 'DELETE': {
        const body: DeleteCommentRequest = await req.json();

        if (!body.id) {
          return errorResponse('id is required');
        }

        // Only allow deleting own comments
        const { error } = await db
          .from('comments')
          .delete()
          .eq('id', body.id)
          .eq('user_id', user.id);

        if (error) throw error;
        return jsonResponse({ message: 'Comment deleted' });
      }

      default:
        return errorResponse('Method not allowed', 405);
    }
  } catch (error) {
    console.error('Database error:', error);
    return errorResponse('Internal server error', 500);
  }
});
