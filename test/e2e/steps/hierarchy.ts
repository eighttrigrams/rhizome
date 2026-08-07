import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// Part-of edges are set through the REST API rather than through the edit
// modal: the scenarios below are about the *mode*, and driving four controls
// per relation line through the modal would make them about the modal instead.
// The API is a first-class write channel here (see /api/describe), and
// recording mode is always on under :dev?/:e2e?, so the write goes through.
async function idOfItem(request: any, title: string): Promise<number> {
  const resp = await request.get(`/api/items?q=${encodeURIComponent(title)}`);
  expect(resp.ok(), `searching for "${title}" failed`).toBeTruthy();
  const items = await resp.json();
  const hit = items.find((i: any) => i.title === title);
  expect(hit, `no item titled "${title}" — found ${JSON.stringify(items.map((i: any) => i.title))}`)
    .toBeTruthy();
  return hit.id;
}

// Leaving the index out is what the modal sends for an empty field: the part
// is a part, it just has no place among its siblings yet.
async function makePartOf(request: any, part: string, whole: string, idx?: number) {
  const data: Record<string, unknown> = {
    "source-id": await idOfItem(request, part),
    "target-id": await idOfItem(request, whole),
    "is-part-of": true,
    reason: "e2e test setup",
  };
  if (idx !== undefined) data["part-of-sort-idx"] = idx;
  const resp = await request.put("/api/relations", { data });
  expect(resp.status(), await resp.text()).toBe(200);
}

When(
  "I make {string} part of {string} at index {int}",
  async ({ request }, part: string, whole: string, idx: number) =>
    makePartOf(request, part, whole, idx),
);

When(
  "I make {string} part of {string} with no index",
  async ({ request }, part: string, whole: string) => makePartOf(request, part, whole),
);

// Same drain pattern as the plain keypress step in contexts.ts: the mode
// toggle goes through fetch-and-reset!, whose late response would otherwise
// clobber whatever the next step does. There is no lhs↔rhs input swap to wait
// for here — the toggle is pressed with no search active.
When(
  "I press the {string} key with shift and alt",
  async ({ page }, key: string) => {
    await page.locator("#main-layer").press(`Alt+Shift+Key${key.toUpperCase()}`);
    await page.waitForTimeout(100);
    await page.waitForLoadState("networkidle");
    await page.evaluate(
      () => new Promise<void>((r) =>
        requestAnimationFrame(() => requestAnimationFrame(() => r())),
      ),
    );
  },
);

Then("I should see {string} in the top strip", async ({ page }, text: string) => {
  await expect(page.locator("#hierarchy-strip")).toContainText(text);
});

Then("I should not see the top strip", async ({ page }) => {
  await expect(page.locator("#hierarchy-strip")).toHaveCount(0);
});

// The strip is not an overlay: it takes its own row and the app below it is
// shorter. So the app's top edge must sit at or below the strip's bottom edge,
// and the app must have lost exactly the strip's height.
Then("the top strip should push the app down", async ({ page }) => {
  const strip = await page.locator("#hierarchy-strip").boundingBox();
  const sides = await page.locator("#sides-container").boundingBox();
  expect(strip).not.toBeNull();
  expect(sides).not.toBeNull();
  expect(strip!.height).toBeGreaterThan(0);
  expect(sides!.y).toBeGreaterThanOrEqual(strip!.y + strip!.height);
  const viewport = page.viewportSize()!;
  expect(Math.round(sides!.height)).toBe(Math.round(viewport.height - strip!.height));
});

// span.title is the item's own title; the card also carries the relation
// annotation, so the card's text as a whole is not the title.
Then("the rhs should list exactly {string}", async ({ page }, expected: string) => {
  const wanted = expected.split(",").map((s) => s.trim());
  await expect(page.locator("#rhs-component li.item-card")).toHaveCount(wanted.length);
  const titles = await page.locator("#rhs-component li.item-card span.title").allInnerTexts();
  expect(titles.map((t) => t.trim())).toEqual(wanted);
});

// The deletion preview plans from the context's stored view over stateless
// REST, so it cannot see hierarchy mode and would list items that are not on
// screen. The button is therefore not offered while the mode is on.
Then("the bulk delete button should be {word}", async ({ page }, state: string) => {
  const button = page.locator("#danger-indicator button");
  if (state === "enabled") await expect(button).toBeEnabled();
  else await expect(button).toBeDisabled();
});
