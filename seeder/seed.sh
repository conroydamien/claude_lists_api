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

echo "Seeding complete"
