import { execSync } from "child_process";
import * as path from "path";
import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";

const configPath = path.resolve(__dirname, "e2e_config.edn");

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

// Single source of truth for the e2e port is the E2E_PORT env var (with the
// canonical default of 3005). The server reads it via e2e_config.edn's
// `#env E2E_PORT` reader tag; playwright doesn't need to parse EDN, it just
// polls the same env-derived value here.
const port = process.env.E2E_PORT || "3005";

// shadow-cljs is built by `make e2e` before this config is loaded -- doing
// the build here, under playwright's webServer wrapper, occasionally hangs
// the child process (no output past shadow-cljs's banner). Keep the wrapper
// to a single JVM that's quick to boot and easy to time out on.
//
// Redirect stdin from /dev/null: when playwright spawns the child in a
// non-tty context, `clj` (the bash wrapper) reads from its stdin and gets
// SIGTTIN if it's still attached to a controlling terminal -- the JVM never
// starts and the webServer times out.
const command = `RHIZOME_CONFIG=${configPath} clj -M -m server < /dev/null`;

const testDir = defineBddConfig({
  features: path.resolve(__dirname, "e2e/features"),
  steps: path.resolve(__dirname, "e2e/steps/*.ts"),
});

export default defineConfig({
  testDir,
  timeout: 60_000,
  workers: 1,
  retries: 2,
  globalSetup: path.resolve(__dirname, "e2e/global-setup.ts"),
  use: {
    baseURL: `http://localhost:${port}`,
    headless: process.env.HEADED !== "1",
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
    cwd: path.resolve(__dirname, ".."),
    url: `http://localhost:${port}`,
    // Just JVM boot + schema load now that shadow-cljs is built ahead of time
    // by `make e2e`. 60s is plenty even on a cold .m2.
    timeout: 60_000,
    reuseExistingServer: false,
  },
});
