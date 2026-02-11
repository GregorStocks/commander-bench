// Generate leaderboard data before astro build.
//
// Locally, `make leaderboard` runs the Python generator via uv before this.
// On Cloudflare Pages, uv is used to install deps and run the generator.
const { execSync } = require("child_process");
const fs = require("fs");

const RESULTS_PATH = "src/data/benchmark-results.json";

if (!fs.existsSync(RESULTS_PATH)) {
  execSync(
    "uv run --project ../puppeteer python ../scripts/generate_leaderboard.py",
    { stdio: "inherit" }
  );
}
