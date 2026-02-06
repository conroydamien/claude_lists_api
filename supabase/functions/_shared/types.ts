/**
 * Shared types for Court Lists API
 *
 * This file defines all data types used for:
 * - REST API requests/responses
 * - WebSocket realtime events
 *
 * Both backend (Edge Functions, triggers) and clients (Android, web)
 * should derive their types from this single source of truth.
 */

// =============================================================================
// REST API Types - Edge Functions
// =============================================================================

/** Request body for /functions/v1/listings */
export interface ListingsRequest {
  date: string; // ISO format: YYYY-MM-DD
}

/** Single entry in listings response */
export interface DiaryEntry {
  dateText: string;      // Display format: "29 January 2024"
  dateIso: string | null; // ISO format: "2024-01-29"
  venue: string;         // e.g., "Dublin Circuit Court"
  type: string;          // e.g., "Criminal", "Civil"
  subtitle: string;      // e.g., "Judge Smith - Court 1"
  updated: string;       // Last updated timestamp
  sourceUrl: string;     // Full URL to case list page
}

/** Response from /functions/v1/listings */
export type ListingsResponse = DiaryEntry[];

/** Request body for /functions/v1/cases */
export interface CasesRequest {
  url: string; // Source URL from DiaryEntry.sourceUrl
}

/** Single parsed case from case list */
export interface ParsedCase {
  listNumber: number | null; // Position in list (1, 2, 3...)
  listSuffix: string | null; // Suffix like "a", "A", "b" for items like "4a", "10b"
  caseNumber: string | null; // e.g., "2024/1234", "CC123/2024"
  title: string;             // Full raw line text
  parties: string | null;    // Extracted party names
  isCase: boolean;           // True if this is a case, false if header
}

/** Response from /functions/v1/cases */
export interface CasesResponse {
  cases: ParsedCase[];
  headers: string[];  // Section headers found in the list
}

// =============================================================================
// REST API Types - PostgREST Tables
// =============================================================================

/** Comment on a case (table: comments) */
export interface Comment {
  id?: number;
  list_source_url: string;
  case_number: string;
  user_id?: string;
  author_name: string;
  content: string;
  created_at?: string;
}

/** Done status for a case (table: case_status) */
export interface CaseStatus {
  list_source_url: string;
  case_number: string;
  done: boolean;
  updated_by?: string;
  updated_at?: string;
}

/** Watched case subscription (table: watched_cases) */
export interface WatchedCase {
  id?: number;
  user_id: string;
  list_source_url: string;
  case_number: string;
  source: 'manual' | 'auto'; // 'auto' = from commenting
  created_at?: string;
}

/** In-app notification (table: notifications) */
export interface AppNotification {
  id: number;
  user_id: string;
  type: NotificationType;
  list_source_url: string;
  case_number: string;
  case_title?: string;
  actor_name: string;
  actor_id?: string;
  content?: string;  // Comment content for 'comment' type
  read: boolean;
  created_at: string;
}

// =============================================================================
// WebSocket Realtime Event Types
// =============================================================================

/**
 * Notification types - used in both notifications table and realtime events
 */
export type NotificationType = 'comment' | 'status_done' | 'status_undone';

/**
 * Base interface for all realtime events.
 * Maps to Supabase postgres_changes payload structure.
 */
export interface RealtimeEvent<T> {
  schema: 'public';
  table: string;
  commit_timestamp: string;
  eventType: 'INSERT' | 'UPDATE' | 'DELETE';
  new: T | null;
  old: T | null;
}

/**
 * Event: Case status changed
 * Source: postgres_changes on 'case_status' table
 * Action: Refresh done status for affected case
 */
export interface CaseStatusChangedEvent extends RealtimeEvent<CaseStatus> {
  table: 'case_status';
}

/**
 * Event: Comment added/deleted
 * Source: postgres_changes on 'comments' table
 * Action: Refresh comment counts and comment list if viewing case
 */
export interface CommentChangedEvent extends RealtimeEvent<Comment> {
  table: 'comments';
}

/**
 * Event: Notification created (for watched case)
 * Source: postgres_changes INSERT on 'notifications' table
 * Action: Show system notification + update unread count
 *
 * This is the primary event for alerting users about activity on watched cases.
 * Created by database triggers when:
 * - Someone comments on a watched case
 * - Someone changes done status on a watched case
 */
export interface NotificationCreatedEvent extends RealtimeEvent<AppNotification> {
  table: 'notifications';
  eventType: 'INSERT';
}

/**
 * Event: Watched cases list changed
 * Source: postgres_changes on 'watched_cases' table
 * Action: Refresh local watched cases set
 */
export interface WatchedCaseChangedEvent extends RealtimeEvent<WatchedCase> {
  table: 'watched_cases';
}

// =============================================================================
// Client-Side Display Types (derived from API types)
// =============================================================================

/**
 * UI model for displaying a court list.
 * Derived from DiaryEntry with additional client-side fields.
 */
export interface CourtList {
  id: number;
  name: string;
  dateIso: string | null;
  dateText: string;
  venue: string;
  type: string | null;
  sourceUrl: string;
}

/**
 * UI model for displaying a case item.
 * Derived from ParsedCase with status and comment data.
 */
export interface CaseItem {
  id: number;
  listSourceUrl: string;
  listNumber: number | null;
  caseNumber: string | null;
  title: string;
  parties: string | null;
  done: boolean;
  commentCount: number;
}

/**
 * Composite key for identifying a case.
 * Used for watched cases, comments, and status tracking.
 */
export interface CaseKey {
  listSourceUrl: string;
  caseNumber: string;
}
