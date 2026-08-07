import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// The edit modal is where a relation's part-of standing is decided, so it is
// the primary UI of the part-of feature — and until these scenarios it was
// covered by nothing: the hierarchy-mode ones deliberately set their edges over
// REST, and there is no cljs test runner in this project. What they exercise
// that nothing else does: the checkbox, the sibling-index field, the parse of
// what was typed (including the transit hop a non-number takes to reach the
// backend as unset) and the refusal banner.

async function drain(page: any) {
  // Same pattern as the keypress steps in contexts.ts: fetch-and-reset!
  // schedules its XHR from a go-block, so give it a tick to reach the wire,
  // then settle the network and let Reagent commit.
  await page.waitForTimeout(100);
  await page.waitForLoadState("networkidle");
  await page.evaluate(
    () => new Promise<void>((r) =>
      requestAnimationFrame(() => requestAnimationFrame(() => r())),
    ),
  );
}

function relationLine(page: any, title: string) {
  return page.locator(".relation").filter({ has: page.locator(`.relation-title:has-text("${title}")`) });
}

When("I reload the app", async ({ page }) => {
  await page.goto("/");
  await page.locator("#main-layer").waitFor({ state: "attached" });
  await page.waitForLoadState("networkidle");
});

When("I select the item {string}", async ({ page }, title: string) => {
  await page.locator("#rhs-component li.item-card").filter({ hasText: title }).first().click();
  await drain(page);
});

When("I select the context {string}", async ({ page }, title: string) => {
  await page.locator("#lhs-component li.item-card").filter({ hasText: title }).first().click();
  await drain(page);
});

When("I open the edit modal", async ({ page }) => {
  // `e` is bound on #main-layer, and the search input swallows every key it
  // sees (ui.main.input.key-handler stops propagation unconditionally), so quit
  // an open search first.
  if ((await page.locator("#search-input").count()) > 0) {
    await page.locator("#search-input").press("Escape");
    await drain(page);
  }
  await page.locator("#main-layer").press("e");
  await expect(page.locator("#modal-component")).toBeVisible();
});

When(
  "I mark the relation to {string} as part of, at index {string}",
  async ({ page }, title: string, index: string) => {
    const line = relationLine(page, title);
    await expect(line).toHaveCount(1);
    const checkbox = line.locator(".relation-part-of input[type=checkbox]");
    if (!(await checkbox.isChecked())) await checkbox.click();
    await line.locator(".relation-sort-idx").fill(index);
  },
);

When("I type {string} into the title field", async ({ page }, text: string) => {
  await page.locator("#item-title").fill(text);
});

// Alt+9 has to be pressed from inside the modal. The save handler sits on a div
// that wraps the modal's content; #modals-component is its *parent* and takes
// only Escape, so a keypress targeted there never reaches the save. The title
// field is where the modal puts focus on mount, which is where the human
// presses it from.
When("I save the modal", async ({ page }) => {
  await page.locator("#item-title").press("Alt+Digit9");
  await drain(page);
});

// A plain relation, over REST: `target` becomes the owner of `source`, which is
// what gives `source`'s modal a line to tick.
When("I link {string} under {string}", async ({ request }, source: string, target: string) => {
  const id = async (title: string) => {
    const resp = await request.get(`/api/items?q=${encodeURIComponent(title)}`);
    const items = await resp.json();
    const hit = items.find((i: any) => i.title === title);
    expect(hit, `no item titled "${title}"`).toBeTruthy();
    return hit.id;
  };
  const resp = await request.put("/api/relations", {
    data: {
      "source-id": await id(source),
      "target-id": await id(target),
      reason: "e2e test setup",
    },
  });
  expect(resp.status(), await resp.text()).toBe(200);
});

Then("the modal should be closed", async ({ page }) => {
  await expect(page.locator("#modal-component")).toHaveCount(0);
});

Then("the modal should still be open", async ({ page }) => {
  await expect(page.locator("#modal-component")).toBeVisible();
});

Then(
  "the relation to {string} should be marked as part of, at index {string}",
  async ({ page }, title: string, index: string) => {
    const line = relationLine(page, title);
    await expect(line).toHaveCount(1);
    await expect(line.locator(".relation-part-of input[type=checkbox]")).toBeChecked();
    await expect(line.locator(".relation-sort-idx")).toHaveValue(index);
  },
);

Then(
  "the modal should say the save was refused, naming {string}",
  async ({ page }, named: string) => {
    const banner = page.locator(".part-of-refusal");
    await expect(banner).toBeVisible();
    await expect(banner).toContainText("part of itself");
    await expect(banner).toContainText(named);
  },
);

Then("the modal should say nothing was saved", async ({ page }) => {
  await expect(page.locator(".part-of-refusal-hint")).toContainText("Nothing was saved");
});

Then("the title field should still read {string}", async ({ page }, text: string) => {
  await expect(page.locator("#item-title")).toHaveValue(text);
});

// A refused save writes nothing at all, the item's own fields included.
Then("the item {string} should still be titled {string}", async ({ request }, q: string, title: string) => {
  const resp = await request.get(`/api/items?q=${encodeURIComponent(q)}`);
  const items = await resp.json();
  expect(items.map((i: any) => i.title)).toContain(title);
});
