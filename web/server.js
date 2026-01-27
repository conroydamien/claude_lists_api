const express = require("express");
const path = require("path");

const app = express();
const PORT = process.env.PORT || 8080;

// Serve static files from the public directory.
app.use(express.static(path.join(__dirname, "public")));

app.listen(PORT, () => {
  console.log(`Web app listening on port ${PORT}`);
});
