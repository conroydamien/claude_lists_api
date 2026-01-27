# Claude Lists API

A to-do list app with a PostgREST API, PostgreSQL database, web frontend, and real-time updates via WebSocket.

## Quick start

```bash
podman-compose down -v && podman-compose build --no-cache && podman-compose up -d
```

Open http://localhost:8080.

## Services

| Service      | Port | Description                                      |
|-------------|------|--------------------------------------------------|
| web         | 8080 | Static web frontend (Node/Express)               |
| postgrest   | 3000 | RESTful API backed by PostgreSQL                 |
| ws-sidecar  | 9000 | WebSocket server broadcasting item changes       |
| db          | 5433 | PostgreSQL 16                                    |

## LAN access (Podman on macOS)

Podman on macOS runs containers inside a Linux VM. With user-mode networking (the default), ports are only forwarded to `localhost` on the host — not to your LAN interface. This means other devices on your network can't connect directly.

To expose the app on your LAN, use `socat` to forward traffic from your LAN IP to localhost:

```bash
brew install socat

LAN_IP=$(ipconfig getifaddr en0)
socat TCP4-LISTEN:8080,bind=$LAN_IP,reuseaddr,fork TCP4:127.0.0.1:8080 &
socat TCP4-LISTEN:3000,bind=$LAN_IP,reuseaddr,fork TCP4:127.0.0.1:3000 &
socat TCP4-LISTEN:9000,bind=$LAN_IP,reuseaddr,fork TCP4:127.0.0.1:9000 &
```

You may need to allow `socat` through the macOS firewall: System Settings > Network > Firewall > Options, then add `/opt/homebrew/bin/socat` (press Cmd+Shift+G in the file picker to navigate to `/opt`).

Then visit `http://<LAN_IP>:8080` from your phone or other device.

To stop the forwards:

```bash
pkill -f "socat.*TCP"
```

## Tests

```bash
python -m pytest tests/ -v
```
