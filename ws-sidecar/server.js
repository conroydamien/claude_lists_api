const { Client } = require("pg");
const { WebSocketServer } = require("ws");

const PG_URI =
  process.env.DATABASE_URL ||
  "postgres://claude_lists:claude_lists@db:5432/claude_lists";

const WS_PORT = Number(process.env.WS_PORT) || 9000;

async function main() {
  const pg = new Client({ connectionString: PG_URI });
  await pg.connect();
  await pg.query("LISTEN items_changed");
  console.log("Listening on Postgres channel: items_changed");

  const wss = new WebSocketServer({ port: WS_PORT });
  console.log(`WebSocket server running on port ${WS_PORT}`);

  pg.on("notification", (msg) => {
    const payload = msg.payload;
    console.log("Notification received:", payload, "clients:", wss.clients.size);
    for (const client of wss.clients) {
      if (client.readyState === 1) {
        client.send(payload);
      }
    }
  });

  pg.on("error", (err) => {
    console.error("Postgres connection error:", err);
    process.exit(1);
  });
}

main().catch((err) => {
  console.error("Failed to start:", err);
  process.exit(1);
});
