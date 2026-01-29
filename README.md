# Claude Lists API

A list management app displaying Irish Circuit Court legal diary data. Features a PostgREST API, PostgreSQL database, web frontend with date/venue filtering, real-time updates via WebSocket, and Keycloak authentication.

## Data Source

The app fetches court diary listings from [courts.ie](https://legaldiary.courts.ie) and parses them into:
- **Lists**: Court diary entries (venue, date, type) with headers in metadata
- **Items**: Individual cases with structured metadata (case number, parties, list number)

The seeder caches fetched HTML in a Docker volume to avoid repeatedly hitting courts.ie. Use `--refresh` to force fresh fetches.

## Quick start

```bash
podman-compose down -v && podman-compose build --no-cache && podman-compose up -d
```

Wait for Keycloak to start, then refresh the JWKS:

```bash
# Wait for Keycloak
until curl -sf http://localhost:8180/realms/claude-lists/.well-known/openid-configuration > /dev/null; do sleep 2; done

# Export JWKS and restart PostgREST
curl -s http://localhost:8180/realms/claude-lists/protocol/openid-connect/certs > ./keycloak/keycloak-jwks.json
podman restart claude-lists-postgrest
```

Open http://localhost:8080 (or https://localhost:8443 for HTTPS).

## Services

| Service       | Port       | Description                                      |
|---------------|------------|--------------------------------------------------|
| web           | 8080/8443  | Web frontend (HTTP/HTTPS) with API proxy         |
| postgrest     | 3000       | RESTful API backed by PostgreSQL                 |
| keycloak      | 8180       | OAuth2/OIDC identity provider                    |
| ws-sidecar    | 9000       | WebSocket server broadcasting changes            |
| db            | 5433       | PostgreSQL 16                                    |
| seeder-courts | -          | Fetches courts.ie data on startup (one-shot)     |

## Re-seeding Data

To re-seed the database with fresh data from courts.ie:

```bash
# Clear existing data
podman exec claude-lists-db psql -U claude_lists -d claude_lists -c "TRUNCATE lists CASCADE;"

# Run seeder (uses cached HTML by default)
podman-compose up seeder-courts

# Or force fresh fetch from courts.ie
podman-compose run --rm seeder-courts python seed.py --refresh
```

## Authentication

Test users (all with password `password`):
- alice
- bob
- charlie

The web app uses Keycloak for login. JWTs include a `role` claim that PostgREST uses for database role switching.

Comments can only be deleted by their author (enforced via Row Level Security).

## LAN access (Podman on macOS)

Podman on macOS runs containers inside a Linux VM. Ports are only forwarded to `localhost`, not your LAN interface.

Use the included script to forward ports:

```bash
./lan-proxy.sh
```

This forwards HTTPS (8443), Keycloak (8180), API (3000), and WebSocket (9000) to your LAN IP.

Then visit `https://<LAN_IP>:8443` from your phone or other device. Accept the self-signed certificate warning.

To stop: press Ctrl+C or run `killall socat`.

## Architecture

See [docs/architecture.puml](docs/architecture.puml) for the system diagram.

Key features:
- **Courts Data**: Fetches and parses Irish Circuit Court legal diary from courts.ie
- **JSONB Metadata**: Flexible metadata storage for lists (headers, venue, date) and items (case number, parties)
- **Filtering**: Date picker and venue dropdown filters in the web UI
- **API Proxy**: The web server proxies `/api/*` to PostgREST, avoiding mixed-content issues with HTTPS
- **HTTPS**: Self-signed certificates in `web/certs/` for secure context (required for Web Crypto API)
- **Real-time**: PostgreSQL NOTIFY triggers push changes to WebSocket clients
- **RLS**: Row Level Security restricts comment deletion to authors
- **Caching**: HTML from courts.ie is cached to avoid repeated fetches

## Tests

```bash
python -m pytest tests/ -v
```

## Refreshing JWKS

When Keycloak is recreated (using H2 in-memory database), it generates new keys. Refresh with:

```bash
curl http://localhost:8180/realms/claude-lists/protocol/openid-connect/certs > ./keycloak/keycloak-jwks.json
podman restart claude-lists-postgrest
```
