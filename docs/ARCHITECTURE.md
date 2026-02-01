# Court Lists - System Design Document

## Overview

Court Lists is a mobile application for tracking Irish court case listings. It provides real-time collaboration features allowing legal professionals to mark cases as done, add comments, and receive notifications about watched cases.

## Architecture Summary

```
┌─────────────────┐     ┌──────────────────────────────────────────┐
│                 │     │              Supabase                     │
│  Android App    │────▶│  ┌────────────┐  ┌───────────────────┐   │
│                 │     │  │   Edge     │  │    PostgreSQL     │   │
│  - Compose UI   │◀────│  │ Functions  │  │  + Realtime (WS)  │   │
│  - ViewModel    │     │  └────────────┘  └───────────────────┘   │
│  - Supabase SDK │     │                                          │
└─────────────────┘     └──────────────────────────────────────────┘
                                    │
                                    ▼
                        ┌───────────────────┐
                        │    courts.ie      │
                        │  (Legal Diary)    │
                        └───────────────────┘
```

## Components

### 1. Android Application

**Technology**: Kotlin, Jetpack Compose, Ktor HTTP/WebSocket

**Key Classes**:
- `CourtListsApplication` - Application-level lifecycle and API client management
- `MainViewModel` - UI state management and business logic
- `CourtListsApi` - REST API client (Ktor HTTP)
- `RealtimeClient` - WebSocket client for realtime events
- `AuthManager` - OAuth2 PKCE authentication and token storage

**Responsibilities**:
- User authentication (Google OAuth via Supabase)
- Display court listings and cases
- Track case status (done/not done)
- Comments on cases
- Watch/unwatch cases for notifications
- Receive and display notifications

### 2. Supabase Backend

#### 2.1 Edge Functions

Serverless functions that proxy requests to courts.ie:

| Function | Purpose |
|----------|---------|
| `listings` | Fetch and parse court listings for a date |
| `cases` | Fetch and parse cases from a listing page |

#### 2.2 PostgreSQL Database

**Tables**:

| Table | Purpose |
|-------|---------|
| `comments` | User comments on cases |
| `case_status` | Done/not-done status per case |
| `watched_cases` | Cases users are watching |
| `notifications` | In-app notification history |

**Triggers**:

| Trigger | Action |
|---------|--------|
| `on_comment_insert_auto_watch` | Auto-watch case when user comments |
| `on_comment_insert_notify` | Create notifications for watchers |
| `on_status_change_notify` | Create notifications on status change |

#### 2.3 Realtime (WebSocket)

PostgreSQL change notifications via Supabase Realtime:

| Table | Events | Client Action |
|-------|--------|---------------|
| `case_status` | INSERT, UPDATE | Refresh case status display |
| `comments` | INSERT, DELETE | Refresh comment counts |
| `notifications` | INSERT | Show system notification |
| `watched_cases` | INSERT, DELETE | Refresh watched set |

### 3. External: courts.ie

The official Irish Courts Service legal diary website. Court listings are scraped and parsed by Edge Functions.

## Data Flow Diagrams

See [architecture.puml](./architecture.puml) for detailed PlantUML diagrams:

| Diagram | Description |
|---------|-------------|
| `component-diagram` | High-level system components and connections |
| `sequence-auth` | Authentication flow with Google OAuth |
| `sequence-listings` | Loading court listings and cases |
| `sequence-notification` | End-to-end notification flow |
| `sequence-realtime-detail` | Realtime subscription internals |
| `class-diagram` | Android data model classes |
| `database-erd` | PostgreSQL entity relationship diagram |

**Rendering PlantUML:**
```bash
# Using PlantUML CLI
plantuml docs/architecture.puml

# Using Docker
docker run -v $(pwd)/docs:/data plantuml/plantuml architecture.puml

# Online: paste content at https://www.plantuml.com/plantuml/uml
```

### Authentication Flow

1. User taps "Sign in with Google"
2. App opens Supabase OAuth flow
3. Supabase redirects to Google
4. User authenticates with Google
5. Callback returns to app with session
6. App checks `app_metadata.approved` flag
7. If approved, load data; otherwise show pending screen

### Listing Cases Flow

1. User selects a date
2. App calls `POST /functions/v1/listings` with date
3. Edge Function fetches courts.ie listings page
4. Edge Function parses HTML, extracts entries
5. Returns JSON array of `DiaryEntry` objects
6. User selects a listing
7. App calls `POST /functions/v1/cases` with URL
8. Edge Function fetches and parses case detail page
9. Returns `CasesResponse` with cases and headers
10. App fetches `case_status` and `comments` counts via PostgREST
11. Display merged data

### Notification Flow

1. User A watches Case X (manual or auto via comment)
2. User B comments on Case X or changes status
3. Database trigger fires
4. Trigger creates `notifications` row for User A
5. Supabase Realtime broadcasts INSERT event
6. User A's app receives WebSocket message
7. `CourtListsApplication` shows system notification
8. `MainViewModel` updates notification badge

## API Reference

See [api.yaml](../supabase/api.yaml) for OpenAPI specification.

### REST Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/functions/v1/listings` | Get listings for date |
| POST | `/functions/v1/cases` | Get cases for listing |
| GET | `/rest/v1/comments` | Get comments |
| POST | `/rest/v1/comments` | Add comment |
| DELETE | `/rest/v1/comments` | Delete comment |
| GET | `/rest/v1/case_status` | Get case statuses |
| POST | `/rest/v1/case_status` | Upsert case status |
| GET | `/rest/v1/watched_cases` | Get watched cases |
| POST | `/rest/v1/watched_cases` | Watch a case |
| DELETE | `/rest/v1/watched_cases` | Unwatch a case |
| GET | `/rest/v1/notifications` | Get notifications |
| PATCH | `/rest/v1/notifications` | Mark as read |

### WebSocket Events

| Table | Event | Payload |
|-------|-------|---------|
| `case_status` | postgres_changes | `CaseStatus` |
| `comments` | postgres_changes | `Comment` |
| `notifications` | postgres_changes (INSERT) | `AppNotification` |
| `watched_cases` | postgres_changes | `WatchedCase` |

## Type Definitions

Canonical types: `supabase/functions/_shared/types.ts`

Android mirror: `android/app/src/main/java/com/claudelists/app/api/Models.kt`

## Security

### Authentication
- Google OAuth via Supabase Auth
- JWT tokens for API access
- Approval gating via `app_metadata.approved`

### Row Level Security (RLS)
- All tables have RLS enabled
- `is_approved()` function checks JWT claims
- Users can only access/modify their own data where applicable

### Edge Function Security
- URL validation (only courts.ie allowed)
- CORS headers configured

## Project Structure

```
claude_lists_api/
├── android/                          # Android application
│   └── app/src/main/java/com/claudelists/app/
│       ├── api/
│       │   ├── Models.kt             # Data models (mirrors _shared/types.ts)
│       │   ├── CourtListsApi.kt      # REST API client (Ktor HTTP)
│       │   ├── RealtimeClient.kt     # WebSocket client for realtime
│       │   └── AuthManager.kt        # OAuth2 PKCE + token storage
│       ├── ui/screens/               # Compose UI screens
│       ├── viewmodel/
│       │   └── MainViewModel.kt      # Business logic
│       ├── CourtListsApplication.kt  # App-level lifecycle management
│       ├── MainActivity.kt           # Entry point
│       └── NotificationHelper.kt     # System notifications
│
├── supabase/
│   ├── functions/
│   │   ├── _shared/
│   │   │   └── types.ts              # Canonical type definitions
│   │   ├── listings/index.ts         # Listings edge function
│   │   └── cases/index.ts            # Cases edge function
│   ├── migrations/
│   │   ├── 001_initial_schema.sql    # comments, case_status
│   │   └── 002_notifications.sql     # watched_cases, notifications, triggers
│   └── api.yaml                      # OpenAPI specification
│
└── docs/
    ├── ARCHITECTURE.md               # This document
    └── architecture.puml             # PlantUML diagrams
```

## Deployment

### Supabase
```bash
# Apply database migrations
supabase db push

# Deploy edge functions
supabase functions deploy listings
supabase functions deploy cases
```

### Android
```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease
```

## Future Considerations

1. **Push Notifications (FCM)**: Currently using in-app realtime only. FCM would enable notifications when app is closed.

2. **Offline Support**: Cache listings and cases locally for offline viewing.

3. **Web Client**: Share types and API with a web application.

4. **Search**: Full-text search across cases and comments.
