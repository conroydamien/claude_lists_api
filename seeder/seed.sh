#!/bin/sh
set -e

API="${API_URL:-http://postgrest:3000}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
REALM="claude-lists"
CLIENT_ID="claude-lists-web"

echo "Waiting for Keycloak to be ready..."
until curl -sf "$KEYCLOAK_URL/realms/$REALM" > /dev/null 2>&1; do
  sleep 2
done
echo "Keycloak is ready"

echo "Getting access token..."
TOKEN_RESPONSE=$(curl -sf "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=$CLIENT_ID" \
  -d "username=admin" \
  -d "password=admin")
ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

if [ -z "$ACCESS_TOKEN" ]; then
  echo "Failed to get access token"
  exit 1
fi
echo "Got access token"

AUTH_HEADER="Authorization: Bearer $ACCESS_TOKEN"

echo "Waiting for API to be ready..."
until curl -sf -H "$AUTH_HEADER" "$API/" > /dev/null 2>&1; do
  sleep 1
done
echo "API is ready"

# Check if data already exists
COUNT=$(curl -sf -H "$AUTH_HEADER" "$API/lists" | grep -c '"id"' || true)
if [ "$COUNT" -gt 0 ]; then
  echo "Seed data already exists ($COUNT lists), skipping"
  exit 0
fi

echo "Seeding lists..."
curl -sf "$API/lists" \
  -H "$AUTH_HEADER" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d '[
    {"name": "Groceries", "description": "Weekly shopping list"},
    {"name": "Project Tasks", "description": "Things to do for the project"},
    {"name": "Books to Read", "description": "Reading list for 2025"}
  ]' > /dev/null

echo "Seeding items..."
curl -sf "$API/items" \
  -H "$AUTH_HEADER" \
  -H "Content-Type: application/json" \
  -d '[
    {"list_id": 1, "title": "Milk", "description": "2 litres, semi-skimmed", "done": false},
    {"list_id": 1, "title": "Bread", "description": "Sourdough loaf", "done": true},
    {"list_id": 1, "title": "Eggs", "description": "Free range, dozen", "done": false},
    {"list_id": 2, "title": "Set up CI pipeline", "description": "GitHub Actions or similar", "done": false},
    {"list_id": 2, "title": "Write API docs", "description": "Document PostgREST endpoints", "done": false},
    {"list_id": 2, "title": "Add JWT auth", "description": "Secure the API with token-based auth", "done": false},
    {"list_id": 3, "title": "Designing Data-Intensive Applications", "description": "Martin Kleppmann", "done": true},
    {"list_id": 3, "title": "The Pragmatic Programmer", "description": "Hunt & Thomas", "done": false}
  ]' > /dev/null

echo "Seeding comments..."
curl -sf "$API/comments" \
  -H "$AUTH_HEADER" \
  -H "Content-Type: application/json" \
  -d '[
    {"item_id": 1, "author_name": "Admin", "content": "Should we get whole milk instead?"},
    {"item_id": 1, "author_name": "Admin", "content": "Semi-skimmed is fine, it is what we usually get."},
    {"item_id": 4, "author_name": "Admin", "content": "I can set this up using GitHub Actions."},
    {"item_id": 4, "author_name": "Admin", "content": "Make sure to add caching for npm dependencies."},
    {"item_id": 4, "author_name": "Admin", "content": "Good idea, will do!"},
    {"item_id": 6, "author_name": "Admin", "content": "Should we use JWT or session-based auth?"},
    {"item_id": 6, "author_name": "Admin", "content": "JWT would be simpler for our API-first approach."}
  ]' > /dev/null

echo "Seeding complete"
