import { execFileSync } from "child_process";
import * as fs from "fs";
import * as path from "path";
import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { settle } from "../settle";

const { When, Then, AfterAll } = createBdd();

const repoRoot = path.resolve(__dirname, "../../..");

// scripts/e2e.sh points the hub at this file by DB_PATH.
const e2eDb = path.join(repoRoot, "test/rhizome-e2e.db");

// The dev and e2e :images folder (et.rz.config/dev-folders), which wrap-imgs
// serves at /imgs/*. Fixtures carry an e2e- prefix because it is the dev
// folder too.
const imagesFolder = path.join(repoRoot, "files/Pictures/Tracked");

// An 8x6 blue PNG. Written under every image name, .jpg and .webp included:
// the browser goes by the bytes, not the suffix, and all the scenarios ask is
// that a picture arrives.
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAIAAABxZ0isAAAAEUlEQVR4nGOQi1qAFTEMpAQASpc0gRQT1roAAAAASUVORK5CYII=",
  "base64",
);

// Mirrors the corner-padding of ui.floating-player, which the viewer matches.
const PADDING = 20;

// What /open/ was asked for, per page. The route answers it here, so nothing
// reaches the server's `open` -- there is no Mac in the box to open it on, and
// the request going out is the whole of what the app does about it.
const opened = new WeakMap<any, string[]>();

async function watchOpens(page: any) {
  if (opened.has(page)) return;
  const seen: string[] = [];
  opened.set(page, seen);
  await page.route("**/open/**", async (route: any) => {
    seen.push(decodeURIComponent(new URL(route.request().url()).pathname.slice("/open/".length)));
    await route.fulfill({ status: 200, body: "" });
  });
}

// The list is newest first (updated_at_ctx, and updated_at outside a context),
// and items made a few milliseconds apart share their second -- measured: the
// order then came out differently from the order of creation. So each one is
// given a second of its own, far enough ahead that the context, made just now,
// sorts after them all: the item made last is at the top.
let made = 0;

function stamp() {
  made += 1;
  return new Date(Date.UTC(2099, 0, 1) + made * 1000).toISOString().slice(0, 19).replace("T", " ");
}

// The app as it read when the viewer opened, to tell whether a key got through.
let behind: string | null = null;

//
// Past the hover preview's 300ms grace first. The viewer comes up under the
// pointer, so the card it was opened from sees the pointer leave, and the lhs
// lets go of that card's preview 300ms later (on-mouse-leave in
// ui.main.rhs.items-list-items) -- a change behind the viewer that no key made.
async function rememberBehind(page: any) {
  await page.waitForTimeout(400);
  await settle(page);
  behind = await page.locator("#main-layer").innerText();
}

// Every image the viewer showed in this scenario, by file.
let shown: string[] = [];

async function noteShown(page: any) {
  const src = await page.locator(".image-viewer-image").getAttribute("src");
  shown.push(decodeURIComponent(src.replace(/^\/imgs\//, "")));
}

function card(page: any, title: string) {
  return page.locator("#rhs-component li.item-card").filter({ hasText: title }).first();
}

// Every fixture image written, so it can be taken away again: the folder is the
// dev one, and a dev session should not find the suite's pictures in it.
const written = new Set<string>();

AfterAll(() => {
  for (const f of written) fs.rmSync(f, { force: true });
  written.clear();
});

// A string as an SQL literal. The values come from the feature file, and a
// title or a file name with an apostrophe would otherwise end the literal.
function sql(text: string) {
  return `'${text.replace(/'/g, "''")}'`;
}

async function makeItem(request: any, contextTitle: string, title: string, file?: string) {
  const found = await request.get(`/api/items?q=${encodeURIComponent(contextTitle)}`);
  expect(found.ok(), `searching for "${contextTitle}" failed`).toBeTruthy();
  const context = (await found.json())
    .find((i: any) => i.title === contextTitle && i["is-context"]);
  expect(context, `no context titled "${contextTitle}"`).toBeTruthy();
  const resp = await request.post("/api/items", {
    data: { title, "context-ids": [context.id], reason: "e2e test setup" },
  });
  expect(resp.status(), await resp.text()).toBe(201);
  const { id } = await resp.json();
  const at = sql(stamp());
  // Only an import sets an item's file (et.rz.hub.repository.insertion.file),
  // and no route reaches that, so the link goes into the row directly.
  const data = file
    ? `data = json_set(coalesce(data, '{}'), '$."resource-links".file', ${sql(file)}),`
    : "";
  execFileSync("sqlite3", [
    e2eDb,
    `UPDATE items SET ${data} updated_at = ${at}, updated_at_ctx = ${at} WHERE id = ${Number(id)};`,
  ]);
  if (file && !file.endsWith(".pdf")) {
    fs.mkdirSync(imagesFolder, { recursive: true });
    const target = path.join(imagesFolder, file);
    fs.writeFileSync(target, PNG);
    written.add(target);
  }
  shown = [];
}

When(
  "{string} holds an item {string} with the file {string}",
  async ({ request }, contextTitle: string, title: string, file: string) =>
    makeItem(request, contextTitle, title, file),
);

When(
  "{string} holds an item {string} with no file",
  async ({ request }, contextTitle: string, title: string) =>
    makeItem(request, contextTitle, title),
);

When("I click the green badge of {string}", async ({ page }, title: string) => {
  await watchOpens(page);
  await card(page, title).locator(".badge", { hasText: "🟢" }).click();
  await settle(page);
  await rememberBehind(page);
});

When("I Option+click the green badge of {string}", async ({ page }, title: string) => {
  await watchOpens(page);
  await card(page, title).locator(".badge", { hasText: "🟢" }).click({ modifiers: ["Alt"] });
  await settle(page);
});

When("{string} is a context", async ({}, title: string) => {
  execFileSync("sqlite3", [e2eDb, `UPDATE items SET is_context = 1 WHERE title = ${sql(title)};`]);
});

// The ⭕ on the item's card in the list, which selects it as the context.
When("I open the context {string} from the list", async ({ page }, title: string) => {
  await page.locator("#rhs-component li.item-card")
    .filter({ has: page.locator("p", { hasText: new RegExp(`^${title}$`) }) }).first()
    .locator(".badge", { hasText: "⭕" }).click();
  await page.waitForLoadState("networkidle");
  await settle(page);
  // Off the cards: a pointer left over one keeps its hover preview standing in
  // the lhs, in place of the selected context's card.
  await page.mouse.move(1, 1);
  await settle(page);
});

When("I click the green badge of {string} in the lhs", async ({ page }, title: string) => {
  await watchOpens(page);
  await page.locator("#lhs-component li.item-card").filter({ hasText: title }).first()
    .locator(".badge", { hasText: "🟢" }).click();
  await settle(page);
});

// The card at the second place the item stands, for an item listed twice.
When("I click the green badge of the 2nd {string}", async ({ page }, title: string) => {
  await watchOpens(page);
  await page.locator("#rhs-component li.item-card").filter({ hasText: title }).nth(1)
    .locator(".badge", { hasText: "🟢" }).click();
  await settle(page);
});

// The order the walk is checked against, stated rather than assumed.
Then("the list should read {string}", async ({ page }, csv: string) => {
  const want = csv.split(",").map((t) => t.trim());
  await expect(page.locator("#rhs-component li.item-card")).toHaveCount(want.length);
  const got = await page.locator("#rhs-component li.item-card").evaluateAll((cards: Element[]) =>
    cards.map((c) => (c.querySelector("p")?.textContent ?? "").trim()));
  expect(got).toEqual(want);
});

When("I press {string} in the viewer", async ({ page }, key: string) => {
  await page.keyboard.press(key);
  await settle(page);
});

When("I click the next button", async ({ page }) => {
  await page.locator(".image-viewer-next").click();
  await settle(page);
});

When("I click the viewer's backdrop", async ({ page }) => {
  // The top-left corner: backdrop, clear of the picture, the bar and the X.
  await page.locator("#image-viewer").click({ position: { x: 5, y: 5 } });
  await settle(page);
});

Then("the image viewer should show {string}", async ({ page }, file: string) => {
  await expect(page.locator(".image-viewer-image")).toHaveAttribute("src", `/imgs/${encodeURIComponent(file)}`);
});

Then("the image should have loaded", async ({ page }) => {
  await expect
    .poll(() => page.locator(".image-viewer-image").evaluate((img: HTMLImageElement) => img.naturalWidth))
    .toBeGreaterThan(0);
});

Then("the image viewer should cover nearly the whole window", async ({ page }) => {
  const frame = (await page.locator(".image-viewer-frame").boundingBox())!;
  const view = page.viewportSize()!;
  expect(frame.x).toBe(PADDING);
  expect(frame.y).toBe(PADDING);
  expect(frame.x + frame.width).toBe(view.width - PADDING);
  expect(frame.height).toBeGreaterThan(view.height * 0.8);
});

Then(
  "the viewer should be at image {int} of {int}, {string}",
  async ({ page }, n: number, of: number, title: string) => {
    await expect(page.locator(".image-viewer-position")).toHaveText(`${n} / ${of}`);
    await expect(page.locator(".image-viewer-title")).toHaveText(title);
    await noteShown(page);
  },
);

Then("the previous button should be disabled", async ({ page }) => {
  await expect(page.locator(".image-viewer-prev")).toBeDisabled();
});

Then("the next button should be disabled", async ({ page }) => {
  await expect(page.locator(".image-viewer-next")).toBeDisabled();
});

Then("the viewer should not have shown {string}", async ({}, file: string) => {
  expect(shown.length).toBeGreaterThan(0);
  expect(shown).not.toContain(file);
  // And every step landed on a different image.
  expect(new Set(shown).size).toBe(shown.length);
});

Then("the image viewer should be gone", async ({ page }) => {
  await expect(page.locator("#image-viewer")).toHaveCount(0);
});

Then("the app should have the focus", async ({ page }) => {
  await expect.poll(() => page.evaluate(() => document.activeElement?.id)).toBe("main-layer");
});

Then("the focus should still be in the image viewer", async ({ page }) => {
  await expect(page.locator("#image-viewer")).toBeVisible();
  const inside = await page.evaluate(() =>
    document.querySelector("#image-viewer")!.contains(document.activeElement));
  expect(inside).toBe(true);
});

Then("the app behind the viewer should be as it was", async ({ page }) => {
  expect(behind, "nothing was remembered when the viewer opened").not.toBeNull();
  await settle(page);
  expect(await page.locator("#main-layer").innerText()).toBe(behind);
});

Then(
  "the file {string} should have been sent to the system's viewer",
  async ({ page }, file: string) => {
    await expect.poll(() => opened.get(page)).toContain(file);
  },
);

Then("the viewer should show the title {string}", async ({ page }, title: string) => {
  await expect(page.locator(".image-viewer-title")).toHaveText(title);
});

Then("the viewer should offer no stepping", async ({ page }) => {
  await expect(page.locator(".image-viewer-prev")).toHaveCount(0);
  await expect(page.locator(".image-viewer-next")).toHaveCount(0);
  await expect(page.locator(".image-viewer-position")).toHaveCount(0);
});

// What is painted in the middle of the code, not what is in the DOM: the code
// can be there and still be drawn under the viewer.
Then("the QR code should be in front of the image viewer", async ({ page }) => {
  const box = (await page.locator(".qr-overlay-code").boundingBox())!;
  const onTop = await page.evaluate(([x, y]) =>
    !!document.elementFromPoint(x, y)?.closest("#qr-overlay"),
    [box.x + box.width / 2, box.y + box.height / 2]);
  expect(onTop, "the image viewer is covering the QR code").toBe(true);
});
