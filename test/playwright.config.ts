import * as path from "path";
import { defineConfig } from "@playwright/test";
import { defineBddConfig } from "playwright-bdd";
import { resolveE2EPort } from "./e2e/port";

// Port + baseURL come from a shared helper so playwright.config.ts and
// global-setup.ts agree on one source (config.edn, $PORT env override).
// The "is anything on the port?" pre-flight lives in the Makefile so it
// runs exactly once before shadow-cljs builds — not on every worker
// re-import of this config (which would race the JVM playwright just
// spawned and kill every worker).
const port = resolveE2EPort();

// shadow-cljs is built by `make e2e` before this config is loaded -- doing
// the build here, under playwright's webServer wrapper, occasionally hangs
// the child process (no output past shadow-cljs's banner). Keep the wrapper
// to a single JVM that's quick to boot and easy to time out on.
//
// The `:e2e` deps alias sets -Drhizome.e2e=1 so config.clj overrides
// :dev?/:e2e?/:bind-host/db path; port and :semsearch come from config.edn.
//
// Redirect stdin from /dev/null: when playwright spawns the child in a
// non-tty context, `clj` (the bash wrapper) reads from its stdin and gets
// SIGTTIN if it's still attached to a controlling terminal -- the JVM never
// starts and the webServer times out.
const command = `clj -M:e2e -m server < /dev/null`;

const baseURL = `http://localhost:${port}`;

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
    baseURL,
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
    url: baseURL,
    // Just JVM boot + schema load now that shadow-cljs is built ahead of time
    // by `make e2e`. 60s is plenty even on a cold .m2.
    timeout: 60_000,
    reuseExistingServer: false,
  },
});
