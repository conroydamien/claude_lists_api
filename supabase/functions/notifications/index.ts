/**
 * API endpoint for notifications.
 *
 * GET /notifications - Get user's notifications (auth required)
 * PATCH /notifications - Mark notification(s) as read (auth required)
 * DELETE /notifications - Delete notification(s) (auth required)
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

interface MarkReadRequest {
  id?: number;       // Mark single notification
  all?: boolean;     // Mark all notifications
}

interface DeleteRequest {
  id?: number;       // Delete single notification
  all?: boolean;     // Delete all notifications
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return handleCors();
  }

  // All operations require authentication
  const token = extractBearerToken(req.headers.get('Authorization'));
  if (!token) {
    return errorResponse('Missing authorization token', 401);
  }

  const user = await validateGoogleToken(token);
  if (!user) {
    return errorResponse('Invalid or expired token', 401);
  }

  const db = getDbClient();
  const url = new URL(req.url);

  // Ensure user exists in database
  await ensureUser(db, user);

  try {
    switch (req.method) {
      case 'GET': {
        const limit = parseInt(url.searchParams.get('limit') || '50');

        const { data, error } = await db
          .from('notifications')
          .select('*')
          .eq('user_id', user.id)
          .order('created_at', { ascending: false })
          .limit(limit);

        if (error) throw error;
        return jsonResponse(data);
      }

      case 'PATCH': {
        // Mark as read
        const body: MarkReadRequest = await req.json();

        if (body.all) {
          // Mark all as read
          const { error } = await db
            .from('notifications')
            .update({ read: true })
            .eq('user_id', user.id)
            .eq('read', false);

          if (error) throw error;
          return jsonResponse({ message: 'All notifications marked as read' });
        } else if (body.id) {
          // Mark single notification as read
          const { error } = await db
            .from('notifications')
            .update({ read: true })
            .eq('id', body.id)
            .eq('user_id', user.id);

          if (error) throw error;
          return jsonResponse({ message: 'Notification marked as read' });
        } else {
          return errorResponse('id or all is required');
        }
      }

      case 'DELETE': {
        const body: DeleteRequest = await req.json();

        if (body.all) {
          // Delete all notifications
          const { error } = await db
            .from('notifications')
            .delete()
            .eq('user_id', user.id);

          if (error) throw error;
          return jsonResponse({ message: 'All notifications deleted' });
        } else if (body.id) {
          // Delete single notification
          const { error } = await db
            .from('notifications')
            .delete()
            .eq('id', body.id)
            .eq('user_id', user.id);

          if (error) throw error;
          return jsonResponse({ message: 'Notification deleted' });
        } else {
          return errorResponse('id or all is required');
        }
      }

      default:
        return errorResponse('Method not allowed', 405);
    }
  } catch (error) {
    console.error('Database error:', error);
    return errorResponse('Internal server error', 500);
  }
});
