# Authentication Architecture

This document describes the authentication flow used by the Court Lists application.

## Overview

The app uses a **token exchange pattern** that combines native Google Sign-In with Supabase for the best user experience while maintaining security.

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Mobile App    │     │    Supabase     │     │  Edge Functions │
│  (iOS/Android)  │     │                 │     │                 │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         │ 1. Native Google      │                       │
         │    Sign-In            │                       │
         ▼                       │                       │
    ┌─────────┐                  │                       │
    │ Google  │                  │                       │
    │   SDK   │                  │                       │
    └────┬────┘                  │                       │
         │                       │                       │
         │ Google ID Token       │                       │
         │◄──────────────────────┤                       │
         │                       │                       │
         │ 2. Exchange token     │                       │
         │    for Supabase       │                       │
         │    session            │                       │
         ├──────────────────────►│                       │
         │                       │                       │
         │ Supabase Access Token │                       │
         │◄──────────────────────┤                       │
         │                       │                       │
         │ 3. API calls with     │                       │
         │    Google ID Token    │                       │
         ├──────────────────────────────────────────────►│
         │                       │                       │
         │ 4. Realtime with      │                       │
         │    Supabase Token     │                       │
         ├──────────────────────►│                       │
         │                       │                       │
```

## Why This Architecture?

### Native Google Sign-In
- **Better UX**: Uses the native Google sign-in sheet (iOS) / One Tap (Android)
- **No browser redirect**: Unlike OAuth flows, users stay in the app
- **Faster**: No round-trip to browser and back

### Dual Token System
1. **Google ID Token** → Used for Edge Function API calls
   - Backend validates directly with Google
   - No Supabase dependency for API auth
   - Portable to other backends

2. **Supabase Access Token** → Used for Realtime subscriptions
   - Required for RLS (Row Level Security) to work
   - Obtained by exchanging Google token with Supabase

## Token Exchange Flow

When a user signs in:

1. **Native Google Sign-In** returns a Google ID token
2. **Exchange with Supabase**: POST to `/auth/v1/token?grant_type=id_token`
   ```json
   {
     "provider": "google",
     "id_token": "<google_id_token>"
   }
   ```
3. **Supabase validates** the Google token and returns a session
4. **App stores both tokens**:
   - Google ID token for API calls
   - Supabase access token for Realtime

## User ID Handling

The app uses **derived UUIDs** from Google user IDs:

```
Google ID: "108212705978139203655"
           ↓
    SHA-256("google:108212705978139203655")
           ↓
Derived UUID: "a1b2c3d4-e5f6-4789-a0b1-c2d3e4f5a6b7"
```

This ensures:
- Consistent user IDs across platforms (iOS, Android, backend)
- UUIDs compatible with PostgreSQL
- No dependency on Supabase's internal user ID assignment

## RLS Policies

Since user_id in tables uses derived UUIDs (not Supabase auth.uid()), RLS policies are configured to:
- **Allow SELECT** for any authenticated user (needed for Realtime)
- **Authorization** handled by Edge Functions using Google token validation

## Files

### iOS
- `AuthManager.swift` - Handles Google Sign-In and token exchange
- `RealtimeClient.swift` - WebSocket connection with Supabase token

### Android
- `AuthManager.kt` - Handles Google Sign-In and token exchange
- `RealtimeClient.kt` - WebSocket connection with Supabase token

### Backend
- `supabase/functions/_shared/auth.ts` - Google token validation
- `supabase/migrations/012_simplify_rls_for_supabase_auth.sql` - RLS policies

## Portability

This architecture is designed to be portable:

1. **Edge Functions** validate Google tokens directly - no Supabase auth dependency
2. **Derived UUIDs** are computed the same way on all platforms
3. **Token exchange pattern** works with any backend that supports Google Sign-In

To migrate to a different backend:
1. Implement Google token validation (same as current Edge Functions)
2. Implement your own realtime system (WebSocket, SSE, etc.)
3. User IDs remain consistent (derived from Google ID)
