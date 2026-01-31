# Supabase Setup

This directory contains the Supabase configuration for the Courts.ie app.

## Setup Steps

### 1. Create Supabase Project

1. Go to [supabase.com](https://supabase.com) and create an account
2. Create a new project
3. Note your project URL and anon key from Settings > API

### 2. Configure Authentication

In the Supabase dashboard:

1. Go to **Authentication > Providers**
2. Enable **Google**:
   - Create OAuth credentials at [Google Cloud Console](https://console.cloud.google.com)
   - Add authorized redirect URI: `https://<your-project>.supabase.co/auth/v1/callback`
   - Enter Client ID and Secret in Supabase
3. Enable **Azure** (optional):
   - Create app registration in Azure Portal
   - Add redirect URI: `https://<your-project>.supabase.co/auth/v1/callback`
   - Enter Client ID and Secret in Supabase

### 3. Run Database Migration

In the Supabase dashboard:

1. Go to **SQL Editor**
2. Paste contents of `migrations/001_initial_schema.sql`
3. Run the query

### 4. Deploy Edge Functions

Install Supabase CLI:
```bash
npm install -g supabase
```

Login and link project:
```bash
supabase login
supabase link --project-ref <your-project-id>
```

Deploy functions:
```bash
supabase functions deploy listings
supabase functions deploy cases
```

### 5. User Approval Workflow

New users sign in but are not approved by default. To approve a user:

1. Go to **Authentication > Users**
2. Click on the user
3. Edit their metadata, add: `{ "approved": true }`
4. Save

Or via SQL:
```sql
UPDATE auth.users
SET raw_app_meta_data = raw_app_meta_data || '{"approved": true}'
WHERE email = 'user@example.com';
```

## API Endpoints

### Edge Functions

- `GET /functions/v1/listings?date=YYYY-MM-DD` - Get court listings for a date
- `GET /functions/v1/cases?url=<source_url>` - Get cases for a specific list

### Database (via PostgREST)

- `GET /rest/v1/comments?list_source_url=eq.<url>&case_number=eq.<num>`
- `POST /rest/v1/comments`
- `DELETE /rest/v1/comments?id=eq.<id>`
- `GET /rest/v1/case_status?list_source_url=eq.<url>&case_number=eq.<num>`
- `POST /rest/v1/case_status` (upsert with `Prefer: resolution=merge-duplicates`)

## Configuration for Apps

### Android

Update `app/src/main/java/.../api/SupabaseClient.kt`:
```kotlin
const val SUPABASE_URL = "https://<your-project>.supabase.co"
const val SUPABASE_ANON_KEY = "<your-anon-key>"
```

### Web

Update in `index.html`:
```javascript
const SUPABASE_URL = 'https://<your-project>.supabase.co'
const SUPABASE_ANON_KEY = '<your-anon-key>'
```
