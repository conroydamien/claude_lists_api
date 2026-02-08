# Court Lists API

A mobile app (iOS + Android) for Irish legal professionals to view and collaborate on court listings from courts.ie.

## Current Priority: Backend Stability

**The Supabase backend (`supabase/functions/`) is being stabilized and should be treated as locked down.**

- Any changes to the backend API require explicit justification
- Mobile apps (iOS/Android) should adapt to the backend, not the other way around
- If a backend change seems necessary, FLAG IT clearly and discuss before implementing
- New features should be implemented in the mobile apps using existing API capabilities where possible
- The API contract (request/response shapes) should not change without versioning consideration

When proposing work, prefer solutions that:
1. Use existing endpoints as-is
2. Add new endpoints rather than modify existing ones
3. Keep backward compatibility if changes are unavoidable

### Backend Change Checklist

If a backend change is unavoidable, complete ALL of these:

- [ ] **Justify**: Explain why this can't be done in the mobile apps
- [ ] **Flag**: Clearly state "BACKEND CHANGE REQUIRED" in the conversation
- [ ] **Backward compat**: Ensure existing mobile app versions won't break
- [ ] **Update OpenAPI**: Update `docs/openapi.yaml` with the changes
- [ ] **Update CLAUDE.md**: Document any new patterns or concepts
- [ ] **Test**: Verify with curl or Swagger before updating mobile apps
- [ ] **Deploy**: Use `npx supabase functions deploy <name> --no-verify-jwt`

## Project Structure

```
├── android/                 # Android app (Kotlin, Jetpack Compose)
├── ios/                     # iOS app (Swift, SwiftUI)
├── supabase/
│   └── functions/           # Edge Functions (Deno/TypeScript)
│       ├── _shared/         # Shared auth, types, utilities
│       ├── listings/        # Fetch court listings by date/court
│       ├── cases/           # Parse case details from list URL
│       ├── case-status/     # Toggle done/undone for cases
│       ├── comments/        # CRUD for comments on cases/lists
│       ├── notifications/   # User notification management
│       └── watched-cases/   # Watch subscriptions for updates
└── docs/                    # OpenAPI spec and Swagger UI
```

## Authentication

**Google ID Tokens** (not Supabase JWT):
- Apps use Google Sign-In to get ID tokens
- Backend validates tokens against `oauth2.googleapis.com/tokeninfo`
- Checks audience matches OAuth client IDs:
  - iOS: `807765424446-baktcv20bbq38s3t4u7j744cl558qbot`
  - Android/Web: `807765424446-ofrgb5gs9l0lo8usfj37258iuj93cfdd`
- Google user ID converted to UUID via SHA-256 hash of `"google:{googleId}"`
- Functions deployed with `--no-verify-jwt` flag

## Court Types

- `circuit-court` - Circuit Court (5-column format: Date, Venue, Type, Subtitle, Updated)
- `high-court` - High Court (4-column format: Date, Venue/Type, Subtitle, Updated)
- `court-of-appeal` - Court of Appeal (3-column format: Date, Type, Updated)

## Case Number Patterns

High Court:
- New style: `H.P.2025.0002747`, `H.COS.2025.0000215`, `H.JR.2025.0001507`
- Bail style: `2025 2073 SS`, `2026 60 SS`
- Old style: `2021 5113 P`, `2022 24 SP`

Circuit Court:
- Standard: `2024/1234`, `123/2024`
- Prefixed: `CC123/2024`, `DS1234/2024`

## Key Concepts

- **case_key**: Unique identifier for a case within a list, format: `{caseNumber}|{listNumber}` or just `{listNumber}`
- **list_number**: The position of a case in the court list (e.g. case #4). Used across all tables to track cases separately by position:
  - `case_status` — part of primary key `(list_source_url, case_number, list_number)`
  - `comments` — stored on each comment, filterable via `?list_number=` query param
  - `watched_cases` — part of unique constraint `(user_id, list_source_url, case_number, list_number)`
  - `notifications` — stored for display purposes
  - Defaults to `0` when not provided (backward compatible with older app versions)
- **is_blocked**: Boolean on `users` table. When `true`, all endpoints return `403 "Account is blocked"`
- **List comments**: Use case_key suffix `__LIST__` for comments on entire list (not specific case)
- **Watchers**: Users can watch cases/lists to receive notifications on status changes or new comments

## Running Tests

```bash
cd supabase/functions/_tests

# Run all unit tests
deno test --allow-read unit/

# Run integration tests (requires token)
export GOOGLE_ID_TOKEN="your-token-here"
deno test --allow-net --allow-env integration/

# Or use the test runner script
./run_tests.sh          # all tests
./run_tests.sh unit     # unit only
./run_tests.sh integration  # integration only
```

### Test Structure
- `_tests/unit/` - Unit tests for parsing and auth helpers (no network)
- `_tests/integration/` - Integration tests against live endpoints (requires GOOGLE_ID_TOKEN)
- `_tests/config.ts` - Test configuration and helpers

## Deploying Functions

```bash
cd supabase
npx supabase functions deploy <function-name> --no-verify-jwt
```

## Running Swagger UI

```bash
cd docs
python3 -m http.server 8080
# Open http://localhost:8080
```

## Building Android

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Building iOS

Open `ios/CourtLists/CourtLists.xcodeproj` in Xcode and build.

## Database Tables (Supabase)

- `users` - User profiles (id UUID from Google, email, name, is_blocked)
- `case_status` - Done/undone status per case, keyed by (list_source_url, case_number, list_number)
- `comments` - Comments on cases and lists, includes list_number
- `watched_cases` - User watch subscriptions, unique by (user_id, list_source_url, case_number, list_number)
- `notifications` - Notification queue, includes list_number

## External Dependencies

- courts.ie Legal Diary: `https://legaldiary.courts.ie`
- Google OAuth tokeninfo: `https://oauth2.googleapis.com/tokeninfo`
- Supabase project: `fbiissfiqgtlenxkjuwv`
