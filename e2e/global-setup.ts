import { chromium, request } from "@playwright/test";

export default async function globalSetup() {
  const port = process.env.PORT || "3005";
  const baseURL = `http://localhost:${port}`;
  const start = Date.now();

  // Warm the JIT by exercising the actual code paths the tests hit:
  // page load, start-context-search, debounced search, new-context.
  const browser = await chromium.launch();
  const page = await browser.newPage({ baseURL });
  const ctx = await request.newContext({ baseURL });
  await ctx.post("/test/reset", { data: { reason: "warmup" } });
  await page.goto("/");
  await page.locator("#main-layer").waitFor({ state: "attached" });
  await page.locator("#main-layer").press("c");
  await page.locator("#search-input").waitFor({ state: "visible" });
  await page.locator("#search-input").fill("WarmUp");
  await page.locator("#search-input").press("Enter");
  await page.waitForLoadState("networkidle");
  await ctx.post("/test/reset", { data: { reason: "warmup-cleanup" } });
  await browser.close();
  await ctx.dispose();
  console.log(`[globalSetup] warmup done in ${Date.now() - start}ms`);
}
