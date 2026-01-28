const express = require("express");
const path = require("path");
const fs = require("fs");
const https = require("https");
const http = require("http");
const { createProxyMiddleware } = require("http-proxy-middleware");

const app = express();
const PORT = process.env.PORT || 8080;
const HTTPS_PORT = process.env.HTTPS_PORT || 8443;
const API_URL = process.env.API_URL || "http://postgrest:3000";
const WS_URL = process.env.WS_URL || "http://ws-sidecar:9000";

// Proxy /api/* to PostgREST (strips /api prefix)
app.use("/api", createProxyMiddleware({
  target: API_URL,
  changeOrigin: true,
  pathRewrite: { "^/api": "" },
}));

// WebSocket proxy for /ws
const wsProxy = createProxyMiddleware({
  target: WS_URL,
  changeOrigin: true,
  ws: true,
});
app.use("/ws", wsProxy);

// Serve static files from the public directory.
app.use(express.static(path.join(__dirname, "public")));

// HTTP server
const httpServer = http.createServer(app);
httpServer.on("upgrade", wsProxy.upgrade);
httpServer.listen(PORT, "0.0.0.0", () => {
  console.log(`Web app listening on http://0.0.0.0:${PORT}`);
  console.log(`API proxy: /api/* -> ${API_URL}`);
  console.log(`WS proxy: /ws -> ${WS_URL}`);
});

// HTTPS server (if certs exist)
const certPath = path.join(__dirname, "certs", "cert.pem");
const keyPath = path.join(__dirname, "certs", "key.pem");

if (fs.existsSync(certPath) && fs.existsSync(keyPath)) {
  const httpsOptions = {
    key: fs.readFileSync(keyPath),
    cert: fs.readFileSync(certPath),
  };
  const httpsServer = https.createServer(httpsOptions, app);
  httpsServer.on("upgrade", wsProxy.upgrade);
  httpsServer.listen(HTTPS_PORT, "0.0.0.0", () => {
    console.log(`Web app listening on https://0.0.0.0:${HTTPS_PORT}`);
  });
}
