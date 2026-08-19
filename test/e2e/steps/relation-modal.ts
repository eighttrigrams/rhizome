import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// The modal a card opens is the same #modal-component the edit modal uses, so
// the "modal is open / closed / refused" steps in edit-modal.ts serve both. What
// is only here is the way in — the annotation strip on the card — and the three
// controls the edge itself carries.

async function drain(page: any) {
  // Same pattern as edit-modal.ts: fetch-and-reset-with-method! schedules its
  // XHR from a go-block, so give it a tick to reach the wire, settle the
  // network, then let Reagent commit.
  await page.waitForTimeout(100);
  await page.waitForLoadState("networkidle");
  await page.evaluate(
    () => new Promise<void>((r) =>
      requestAnimationFrame(() => requestAnimationFrame(() => r())),
    ),
  );
}

function card(page: any, title: string) {
  return page.locator("#rhs-component li.item-card").filter({
    has: page.locator(`span.title:has-text("${title}")`),
  });
}

// A second context, over REST rather than through the search: creating one in
// the UI is refused while a context is selected (see
// ui.main.input.key-handler), and this scenario needs one alongside the
// selection rather than instead of it.
When("I create the context {string}", async ({ request }, title: string) => {
  const resp = await request.post("/api/contexts", {
    data: { title, reason: "e2e test setup" },
  });
  expect(resp.status(), await resp.text()).toBe(201);
});

When("I open the relation modal on {string}", async ({ page }, title: string) => {
  const strip = card(page, title).first().locator(".relation-annotation");
  await expect(strip).toHaveCount(1);
  await strip.click();
  await expect(page.locator("#modal-component")).toBeVisible();
});

Then("the relation modal should be titled {string}", async ({ page }, title: string) => {
  await expect(page.locator("#modal-component h3")).toHaveText(title);
});

When(
  "I mark the relation as part of, at index {string}",
  async ({ page }, index: string) => {
    const checkbox = page.locator("#relation-part-of-input");
    if (!(await checkbox.isChecked())) await checkbox.click();
    await page.locator("#relation-part-of-sort-idx-input").fill(index);
  },
);

When("I untick the badge on the relation", async ({ page }) => {
  const checkbox = page.locator("#relation-show-badge-input");
  if (await checkbox.isChecked()) await checkbox.click();
});

When(
  "I type {string} into the relation annotation field",
  async ({ page }, text: string) => {
    await page.locator("#relation-annotation-input").fill(text);
  },
);

// Alt+9 has to be pressed from inside the modal — see the note on the edit
// modal's save step. The global annotation field is where this modal puts focus
// on mount, so it is where the human presses it from.
When("I save the relation modal", async ({ page }) => {
  await page.locator("#global-annotation-input").press("Alt+Digit9");
  await drain(page);
});

// Escape is handled by the modal's own key handler, which sits on a div inside
// #modals-component -- pressing it on #main-layer, the way the plain keypress
// step does, would reach the app underneath instead.
When("I close the relation modal", async ({ page }) => {
  await page.locator("#global-annotation-input").press("Escape");
  await expect(page.locator("#modal-component")).toHaveCount(0);
  await drain(page);
});

Then(
  "the relation should be marked as part of, at index {string}",
  async ({ page }, index: string) => {
    await expect(page.locator("#relation-part-of-input")).toBeChecked();
    await expect(page.locator("#relation-part-of-sort-idx-input")).toHaveValue(index);
  },
);

Then(
  "the relation annotation field should still read {string}",
  async ({ page }, text: string) => {
    await expect(page.locator("#relation-annotation-input")).toHaveValue(text);
  },
);

// The badge is what `show-badge?` decides (ui.main.context-badges), and it is
// only ever drawn for a context other than the selected one — which is why this
// is asserted from the *other* context the item sits in.
Then(
  "the card for {string} should carry a badge for {string}",
  async ({ page }, title: string, badge: string) => {
    await expect(card(page, title).first().locator("span.badge", { hasText: badge }))
      .toHaveCount(1);
  },
);

Then(
  "the card for {string} should not carry a badge for {string}",
  async ({ page }, title: string, badge: string) => {
    await expect(card(page, title).first().locator("span.badge", { hasText: badge }))
      .toHaveCount(0);
  },
);
