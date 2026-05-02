import { execSync } from "child_process";
import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";

const configPath = "./e2e_config.edn";

// Refuse to run while the dev server is up. `shadow-cljs release app` would
// clobber the `main.js` the dev session is serving, leaving it without the
// hot-reload runtime until the next `watch` rebuild — confusing and easy to
// miss. Make the user shut dev down explicitly.
const devPort = "3006";
try {
  const pids = execSync(
    `lsof -nP -iTCP:${devPort} -sTCP:LISTEN -t`,
    { encoding: "utf-8" },
  ).trim();
  if (pids) {
    console.error(
      `\nDev server is running on :${devPort} (pid ${pids}). E2E builds a release ` +
      `bundle that would overwrite the dev session's main.js.\n` +
      `Tear it down first (\`make stop\`) and re-run.\n`,
    );
    process.exit(1);
  }
} catch (e: any) {
  // lsof exits 1 when nothing is listening — that's the happy path.
}

let port = process.env.PORT;
if (!port) {
  try {
    port = execSync(`bb -e '(:port (read-string (slurp "${configPath}")))'`, { encoding: "utf-8" }).trim();
  } catch {}
}
if (!port) throw new Error(`PORT env var not set and could not read :port from ${configPath}`);

const command =
  `npx shadow-cljs release app && RHIZOME_CONFIG=${configPath} clj -M -m server`;

const testDir = defineBddConfig({
  features: "./e2e/features",
  steps: "./e2e/steps/*.ts",
});

export default defineConfig({
  testDir,
  timeout: 60_000,
  workers: 1,
  retries: 2,
  globalSetup: "./e2e/global-setup.ts",
  use: {
    baseURL: `http://localhost:${port}`,
    headless: true,
  },
  projects: [{
    name: "chromium",
    use: {
      browserName: "chromium",
      ...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
        ? { launchOptions: {
              executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
              args: ["--no-sandbox", "--disable-dev-shm-usage"],
            } }
        : {}),
    },
  }],
  webServer: {
    command,
    url: `http://localhost:${port}`,
    timeout: 120_000,
    reuseExistingServer: false,
  },
});
