#!/bin/bash
# Forward ports from LAN IP to localhost for Podman on macOS

set -e

LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)

if [ -z "$LAN_IP" ]; then
  echo "Could not detect LAN IP"
  exit 1
fi

echo "Starting LAN proxy on $LAN_IP..."

# Cleanup on exit
cleanup() {
  echo ""
  echo "Stopping proxies..."
  kill $(jobs -p) 2>/dev/null
  exit 0
}
trap cleanup SIGINT SIGTERM

# Start socat proxies
socat TCP-LISTEN:8443,bind=$LAN_IP,fork,reuseaddr TCP:localhost:8443 &
echo "  https://$LAN_IP:8443 -> localhost:8443 (Web HTTPS)"

socat TCP-LISTEN:8180,bind=$LAN_IP,fork,reuseaddr TCP:localhost:8180 &
echo "  http://$LAN_IP:8180  -> localhost:8180 (Keycloak)"

socat TCP-LISTEN:3000,bind=$LAN_IP,fork,reuseaddr TCP:localhost:3000 &
echo "  http://$LAN_IP:3000  -> localhost:3000 (API)"

socat TCP-LISTEN:9000,bind=$LAN_IP,fork,reuseaddr TCP:localhost:9000 &
echo "  ws://$LAN_IP:9000    -> localhost:9000 (WebSocket)"

echo ""
echo "LAN proxy running. Access app at: https://$LAN_IP:8443"
echo "Press Ctrl+C to stop."

# Wait for all background jobs
wait
