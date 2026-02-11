// Generate leaderboard data before astro build.
//
// Locally, `make leaderboard` runs the Python generator via uv before this.
// On Cloudflare Pages, this script installs deps via pip and runs the
// generator directly (uv is not available in the CF build environment).
const { execSync } = require("child_process");
const fs = require("fs");

const RESULTS_PATH = "src/data/benchmark-results.json";

if (!fs.existsSync(RESULTS_PATH)) {
  execSync(
    "python3 -m pip install openskill ../puppeteer && python3 ../scripts/generate_leaderboard.py",
    { stdio: "inherit" }
  );
}
