#!/bin/sh
set -e

API="${API_URL:-http://postgrest:3000}"

echo "Waiting for API to be ready..."
until curl -sf "$API/" > /dev/null 2>&1; do
  sleep 1
done
echo "API is ready"

# Check if data already exists
COUNT=$(curl -sf "$API/lists" | grep -c '"id"' || true)
if [ "$COUNT" -gt 0 ]; then
  echo "Seed data already exists ($COUNT lists), skipping"
  exit 0
fi

echo "Seeding lists..."
curl -sf "$API/lists" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d '[
    {"name": "Groceries", "description": "Weekly shopping list"},
    {"name": "Project Tasks", "description": "Things to do for the project"},
    {"name": "Books to Read", "description": "Reading list for 2025"}
  ]' > /dev/null

echo "Seeding items..."
curl -sf "$API/items" \
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
  -H "Content-Type: application/json" \
  -d '[
    {"item_id": 1, "author_name": "Alice", "content": "Should we get whole milk instead?"},
    {"item_id": 1, "author_name": "Bob", "content": "Semi-skimmed is fine, it is what we usually get."},
    {"item_id": 4, "author_name": "Alice", "content": "I can set this up using GitHub Actions."},
    {"item_id": 4, "author_name": "Charlie", "content": "Make sure to add caching for npm dependencies."},
    {"item_id": 4, "author_name": "Alice", "content": "Good idea, will do!"},
    {"item_id": 6, "author_name": "Bob", "content": "Should we use JWT or session-based auth?"},
    {"item_id": 6, "author_name": "Alice", "content": "JWT would be simpler for our API-first approach."}
  ]' > /dev/null

echo "Seeding complete"
