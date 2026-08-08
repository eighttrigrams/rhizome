import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

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

// Right-click on a row is the unlink gesture (alt-right-click is delete, which
// is why the modifier is deliberately absent here). It goes through
// window.confirm, and Playwright dismisses dialogs unless something accepts
// them — an unaccepted confirm would mean nothing was ever attempted and every
// assertion below would be about a gesture that never happened.
When("I unlink the row {string} from the list", async ({ page }, title: string) => {
  const accept = (d: any) => d.accept();
  page.once("dialog", accept);
  await page.locator("#rhs-component li.item-card").filter({ hasText: title }).first()
    .click({ button: "right" });
  await drain(page);
});

When(
  "I also file {string} under a context {string}",
  async ({ request }, itemTitle: string, contextTitle: string) => {
    const created = await request.post("/api/contexts", {
      data: { title: contextTitle, reason: "e2e test setup" },
    });
    expect(created.status(), await created.text()).toBe(201);
    const context = await created.json();
    const found = await request.get(`/api/items?q=${encodeURIComponent(itemTitle)}`);
    const items = await found.json();
    const hit = items.find((i: any) => i.title === itemTitle);
    expect(hit, `no item titled "${itemTitle}"`).toBeTruthy();
    const linked = await request.put("/api/relations", {
      data: { "source-id": hit.id, "target-id": context.id, reason: "e2e test setup" },
    });
    expect(linked.status(), await linked.text()).toBe(200);
  },
);

Then(
  "the list should say the unlink was refused, naming {string} and {string}",
  async ({ page }, row: string, whole: string) => {
    const banner = page.locator("#rhs-component .part-of-refusal");
    await expect(banner).toBeVisible();
    await expect(banner).toContainText("Refused");
    await expect(banner).toContainText(row);
    await expect(banner).toContainText(whole);
  },
);

Then("the list should say nothing was unlinked", async ({ page }) => {
  await expect(page.locator("#rhs-component .part-of-refusal-hint"))
    .toContainText("Nothing was unlinked");
});

Then("the list should not say an unlink was refused", async ({ page }) => {
  await expect(page.locator("#rhs-component .part-of-refusal")).toHaveCount(0);
});
