const { Client } = require("pg");
const { WebSocketServer } = require("ws");

const PG_URI =
  process.env.DATABASE_URL ||
  "postgres://claude_lists:claude_lists@db:5432/claude_lists";

const WS_PORT = Number(process.env.WS_PORT) || 9000;
const RECONNECT_DELAY = 3000;

const wss = new WebSocketServer({ port: WS_PORT });
console.log(`WebSocket server running on port ${WS_PORT}`);

function connectPG() {
  const pg = new Client({ connectionString: PG_URI });

  pg.connect()
    .then(() => pg.query("LISTEN items_changed"))
    .then(() => pg.query("LISTEN comments_changed"))
    .then(() => console.log("Listening on Postgres channels: items_changed, comments_changed"))
    .catch((err) => {
      console.error("Postgres connect failed, retrying:", err.message);
      setTimeout(connectPG, RECONNECT_DELAY);
    });

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
    console.error("Postgres connection lost, reconnecting:", err.message);
    pg.end().catch(() => {});
    setTimeout(connectPG, RECONNECT_DELAY);
  });
}

connectPG();
