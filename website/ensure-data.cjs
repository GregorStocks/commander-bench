// Ensure benchmark-results.json exists so `astro build` works even when
// the Python leaderboard generator hasn't run (e.g. Cloudflare Pages).
const fs = require("fs");
const path = "src/data/benchmark-results.json";
if (!fs.existsSync(path)) {
  fs.mkdirSync("src/data", { recursive: true });
  fs.writeFileSync(
    path,
    JSON.stringify({ generatedAt: "", totalGames: 0, models: [] })
  );
}
