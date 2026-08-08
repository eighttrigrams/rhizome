import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// Deliberately without the waitForLoadState("networkidle") that every other
// step file drains on. Once a video is on the page the embed keeps talking to
// YouTube for as long as it is mounted, so the network never goes idle and the
// wait runs to the test timeout — measured: the first version of this file
// timed out in exactly the step that pressed a key with the player up. Give
// the go-block its tick, let Reagent commit, and lean on the retrying
// assertions for the rest.
async function settle(page: any) {
  await page.waitForTimeout(150);
  await page.evaluate(
    () => new Promise<void>((r) =>
      requestAnimationFrame(() => requestAnimationFrame(() => r())),
    ),
  );
}

// The description is the reachable half of display-youtube-video from out
// here: /api/items takes a description but has no way to set the
// :youtube-video resource link. The two differ only in where the address is
// read from — both hand the same untouched string to the same component — and
// it is the description path that also has to avoid the embed/ rewrite done
// one line later, so it is the sharper of the two to pin.
When(
  "the item {string} has {string} in its description",
  async ({ request }, title: string, url: string) => {
    const found = await request.get(`/api/items?q=${encodeURIComponent(title)}`);
    expect(found.ok(), `searching for "${title}" failed`).toBeTruthy();
    const items = await found.json();
    const hit = items.find((i: any) => i.title === title);
    expect(hit, `no item titled "${title}"`).toBeTruthy();
    const resp = await request.put(`/api/items/${hit.id}`, {
      data: { description: `Notes on this one.\n\n${url}\n`, reason: "e2e test setup" },
    });
    expect(resp.status(), await resp.text()).toBe(200);
  },
);

// Not the shared "I select the item" step: that one drains on networkidle,
// and this is the click that puts the player on the page in the first place.
When("I open the item {string}", async ({ page }, title: string) => {
  await page.locator("#rhs-component li.item-card").filter({ hasText: title }).first().click();
  await settle(page);
});

When("I hover the item {string}", async ({ page }, title: string) => {
  await page.locator("#rhs-component li.item-card").filter({ hasText: title }).first().hover();
  // The hover fetches the description on its own path (fetch-item-description!),
  // which merges rather than resets — but it still has to land before the
  // preview can be asserted on.
  await settle(page);
});

When("I press Escape in the app", async ({ page }) => {
  await page.locator("#main-layer").press("Escape");
  await settle(page);
});

When("I open the QR code", async ({ page }) => {
  await page.locator(".qr-open").first().click();
  await expect(page.locator("#qr-overlay")).toBeVisible();
});

When("I click the QR overlay's close button", async ({ page }) => {
  await page.locator(".qr-overlay-close").click();
});

When("I press Escape in the QR overlay", async ({ page }) => {
  // Pressed on the overlay itself, which is where the focus is: that is the
  // whole arrangement under test. page.keyboard would go to whatever has the
  // focus anyway, but naming the element says what is being relied on.
  await page.locator("#qr-overlay").press("Escape");
});

Then("the video should offer a QR code", async ({ page }) => {
  await expect(page.locator("iframe")).toHaveCount(1);
  await expect(page.locator(".qr-open")).toHaveCount(1);
});

Then("the video should not offer a QR code", async ({ page }) => {
  await expect(page.locator(".qr-open")).toHaveCount(0);
});

Then("the preview should show the video", async ({ page }) => {
  await expect(page.locator("#lhs-component iframe")).toHaveCount(1);
});

Then("the QR overlay should cover the page", async ({ page }) => {
  const overlay = page.locator("#qr-overlay");
  await expect(overlay).toBeVisible();
  const covers = await overlay.evaluate((el) => {
    const r = el.getBoundingClientRect();
    return r.width >= window.innerWidth && r.height >= window.innerHeight
      && r.left <= 0 && r.top <= 0;
  });
  expect(covers, "the overlay does not reach every edge of the viewport").toBeTruthy();
  // Translucent, not opaque — the page is meant to stay faintly there.
  const alpha = await overlay.evaluate((el) => {
    const m = getComputedStyle(el).backgroundColor.match(/rgba?\(([^)]+)\)/);
    const parts = m![1].split(",").map((s) => parseFloat(s));
    return parts.length === 4 ? parts[3] : 1;
  });
  expect(alpha).toBeGreaterThan(0);
  expect(alpha).toBeLessThan(1);
});

Then("the QR overlay should be gone", async ({ page }) => {
  await expect(page.locator("#qr-overlay")).toHaveCount(0);
});

// Read the drawn modules back out of the SVG and compare them with the code
// for `url`, encoded here from the same library. That does not prove a camera
// can read it — nothing available in this box can decode one — but it does
// pin the failure the feature is actually exposed to: handing the encoder the
// wrong string. A code built from the embed/ address would look exactly as
// convincing on screen and take a phone somewhere else.
async function renderedModules(page: any): Promise<string> {
  return page.locator("#qr-overlay svg").evaluate((svg: SVGElement) => {
    const side = parseInt(svg.getAttribute("viewBox")!.split(" ")[3], 10);
    const d = svg.querySelector("path")!.getAttribute("d")!;
    const grid = Array.from({ length: side }, () => new Array(side).fill(0));
    for (const m of d.matchAll(/M(\d+),(\d+)h1v1h-1z/g)) grid[+m[2]][+m[1]] = 1;
    return grid.map((r) => r.join("")).join("\n");
  });
}

// Mirrors ui.qr-overlay/code-svg: same library, same type/ECC, same quiet zone.
function expectedModules(url: string): string {
  const qrcode = require("qrcode-generator");
  const QUIET = 4;
  const qr = qrcode(0, "M");
  qr.addData(url);
  qr.make();
  const n = qr.getModuleCount();
  const side = n + 2 * QUIET;
  const grid = Array.from({ length: side }, () => new Array(side).fill(0));
  for (let r = 0; r < n; r += 1) {
    for (let c = 0; c < n; c += 1) if (qr.isDark(r, c)) grid[r + QUIET][c + QUIET] = 1;
  }
  return grid.map((r) => r.join("")).join("\n");
}

Then("the QR code should encode {string}", async ({ page }, url: string) => {
  expect(await renderedModules(page)).toBe(expectedModules(url));
});

Then("the QR code should not encode the embed address", async ({ page }) => {
  const embedSrc = await page.locator("iframe").first().getAttribute("src");
  expect(embedSrc, "the iframe should be built from the embed/ form").toContain("/embed/");
  expect(await renderedModules(page)).not.toBe(expectedModules(embedSrc!));
});

Then("the item view should still be open", async ({ page }) => {
  await expect(page.locator("#lhs-component .details-component")).toHaveCount(1);
  await expect(page.locator(".qr-open")).toHaveCount(1);
});

Then("the item view should be closed", async ({ page }) => {
  await expect(page.locator(".qr-open")).toHaveCount(0);
});
