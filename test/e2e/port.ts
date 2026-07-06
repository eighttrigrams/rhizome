import * as fs from "fs";
import * as path from "path";

// Single source of truth for the e2e port. Reads config.edn (two valid
// shapes: `:port 3140` literal or `:port #long #or [#env PORT 3140]`
// aero fallback) and lets a runtime PORT env var override.
export function resolveE2EPort(): string {
  const configPath = path.resolve(__dirname, "..", "..", "config.edn");
  const configEdn = fs.readFileSync(configPath, "utf-8");
  const portLine = configEdn.split("\n").find((l) => /:port\b/.test(l));
  const matches = portLine?.match(/\d+/g);
  const portFromConfig = matches?.[matches.length - 1];
  if (!portFromConfig) {
    throw new Error("Could not find :port in config.edn — onboard first.");
  }
  return process.env.PORT || portFromConfig;
}

export function resolveE2EBaseURL(): string {
  return `http://localhost:${resolveE2EPort()}`;
}
