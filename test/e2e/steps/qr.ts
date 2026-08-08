import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
// Playwright bundles pngjs for its own screenshot comparison, so a real pixel
// readback costs no new dependency — which matters in a box that installs
// nothing. It is a private path and could move on an upgrade; if it does, the
// require throws and these scenarios go red, which is the failure mode to want.
// A legibility check that quietly skipped itself would be worse than none.
const { PNG } = require("playwright-core/lib/utilsBundle");

const { When, Then } = createBdd();

// WCAG relative luminance and contrast ratio. Both checks below are about one
// question — can this be told apart from what is behind it — and that question
// has a standard answer, so it is not reinvented here.
function luminance(r: number, g: number, b: number): number {
  const f = (v: number) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function contrast(a: number, b: number): number {
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

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

// The icon was once #8b8878 on the panel's own rgb(136,131,131): 1.05:1, where
// 1.0 is the same colour. It was rendered, laid out, hit-testable and hoverable
// — so toBeVisible passed, a screenshot showed the page as the user saw it, and
// nothing in the suite had an opinion. Only a comparison of the ink with what
// is behind it says anything at all here.
//
// The floor is the app's own body text on the same panel rather than a number
// picked out of the air. That text sits at ~2.24:1, under the 3:1 WCAG asks of
// a control, so any absolute standard would fail this app everywhere and the
// honest fix would be a repaint of the whole panel, not of one icon. What can
// be said without argument is that a control must not be *less* visible than
// the prose beside it.
Then("the QR icon should be no fainter than the text beside it", async ({ page }) => {
  const measured = await page.locator(".qr-open").evaluate((icon: HTMLElement) => {
    const parse = (s: string) => {
      const m = s.match(/rgba?\(([^)]+)\)/);
      return m ? m[1].split(",").map(parseFloat) : null;
    };
    // The nearest ancestor that actually paints. Backgrounds here are opaque or
    // absent, so the first one found is the one on screen.
    const behind = (el: HTMLElement | null) => {
      for (let cur = el; cur; cur = cur.parentElement) {
        const p = parse(getComputedStyle(cur).backgroundColor);
        if (p && (p.length < 4 || p[3] > 0)) return p;
      }
      return null;
    };
    const prose = document.querySelector("#lhs-component .details-component .description");
    return {
      ink: parse(getComputedStyle(icon).color),
      ground: behind(icon),
      proseInk: prose ? parse(getComputedStyle(prose).color) : null,
    };
  });
  expect(measured.ground, "nothing behind the icon paints a background").toBeTruthy();
  expect(measured.proseInk, "no prose on the panel to compare against").toBeTruthy();
  const ground = luminance(measured.ground![0], measured.ground![1], measured.ground![2]);
  const icon = contrast(luminance(measured.ink![0], measured.ink![1], measured.ink![2]), ground);
  const prose = contrast(
    luminance(measured.proseInk![0], measured.proseInk![1], measured.proseInk![2]),
    ground,
  );
  expect(
    icon,
    `the icon is at ${icon.toFixed(2)}:1 against what is behind it, fainter than the `
      + `panel's own text at ${prose.toFixed(2)}:1`,
  ).toBeGreaterThanOrEqual(prose - 0.01);
});

// Same class of check, one layer down and where it actually decides whether the
// feature works: a code drawn dark-on-dark scans as nothing and looks entirely
// fine in a screenshot. Read off the composited page rather than off the CSS —
// what the SVG declares is not evidence about what reached the glass, and the
// white ground could be defeated by anything painting over it.
Then("the QR code should be legible against the overlay", async ({ page }) => {
  const png = PNG.sync.read(await page.locator("#qr-overlay svg").screenshot());
  const lumAt = (x: number, y: number) => {
    const i = (png.width * y + x) << 2;
    return luminance(png.data[i], png.data[i + 1], png.data[i + 2]);
  };
  const all: number[] = [];
  for (let y = 0; y < png.height; y += 1) for (let x = 0; x < png.width; x += 1) all.push(lumAt(x, y));
  all.sort((a, b) => a - b);
  const dark = all[Math.floor((all.length - 1) * 0.05)];
  const light = all[Math.floor((all.length - 1) * 0.95)];

  // Not merely "far apart": the light end has to be genuinely light. Losing the
  // white ground would leave the light modules showing the milky overlay, and
  // dark-on-darker can still be a wide ratio while being unscannable.
  expect(light, "the light modules are not light — is the white ground painting?")
    .toBeGreaterThan(0.8);
  expect(dark, "the dark modules are not dark").toBeLessThan(0.2);
  expect(
    contrast(dark, light),
    `the code is at ${contrast(dark, light).toFixed(1)}:1 between its light and dark modules`,
  ).toBeGreaterThanOrEqual(7);

  // And the quiet zone: four clear modules on every side, or a scanner runs the
  // page into the symbol. Sampled as a ring just inside the edge, which is well
  // within that margin at any size the overlay gives the code.
  const inset = 3;
  const ring: number[] = [];
  for (let x = inset; x < png.width - inset; x += 1) {
    ring.push(lumAt(x, inset), lumAt(x, png.height - 1 - inset));
  }
  for (let y = inset; y < png.height - inset; y += 1) {
    ring.push(lumAt(inset, y), lumAt(png.width - 1 - inset, y));
  }
  expect(Math.min(...ring), "something dark is sitting in the quiet zone").toBeGreaterThan(0.8);
});

Then("the item view should still be open", async ({ page }) => {
  await expect(page.locator("#lhs-component .details-component")).toHaveCount(1);
  await expect(page.locator(".qr-open")).toHaveCount(1);
});

Then("the item view should be closed", async ({ page }) => {
  await expect(page.locator(".qr-open")).toHaveCount(0);
});
