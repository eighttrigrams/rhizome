import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// The way into the modal, the way text is put into it and the way it is saved all
// live in relation-modal.ts / relation-description.ts and serve this feature too.
// What is only here is the bar over the text and the two read-only things that
// stand in the editor's place under it.

async function drain(page: any) {
  // Same pattern as the other modal step files: the fetches this modal makes are
  // scheduled from a go-block, so give them a tick to reach the wire, settle the
  // network, then let Reagent commit (it flushes on requestAnimationFrame, which
  // lags the network layer).
  await page.waitForTimeout(100);
  await page.waitForLoadState("networkidle");
  await page.evaluate(
    () => new Promise<void>((r) =>
      requestAnimationFrame(() => requestAnimationFrame(() => r())),
    ),
  );
}

const bar = (page: any) => page.locator("#modal-component .version-bar");
const label = (page: any) => page.locator("#modal-component .version-bar-label");
const back = (page: any) => page.locator("#modal-component .relation-version-back");
const forward = (page: any) => page.locator("#modal-component .relation-version-forward");
const pastPane = (page: any) => page.locator("#modal-component .relation-version-past");
const provenancePane = (page: any) => page.locator("#modal-component .relation-provenance");

Then("the relation version bar should read {string}", async ({ page }, text: string) => {
  await expect(bar(page)).toBeVisible();
  // The label is the one thing on the bar that says which version is on screen,
  // so it is compared whole rather than by substring: "Version 1 · app" and
  // "Version 1 (current) · app" differ in exactly the fact worth checking.
  await expect(label(page)).toHaveText(text);
});

Then("there should be no earlier relation version to step back to", async ({ page }) => {
  await expect(back(page)).toBeDisabled();
});

When("I step back a relation version", async ({ page }) => {
  await back(page).click();
  await drain(page);
});

When("I step forward a relation version", async ({ page }) => {
  await forward(page).click();
  await drain(page);
});

Then("the older relation version should read {string}", async ({ page }, text: string) => {
  await expect(pastPane(page)).toBeVisible();
  await expect(pastPane(page)).toContainText(text);
});

// The editor being absent is half of what makes a past version read-only, and it
// is also what makes a save from there write the standing text rather than the one
// on screen: ui.modals.annotation-edit/get-values finds the editor by id, and
// falls back to the loaded text when there is none.
Then("the relation text editor should be gone", async ({ page }) => {
  await expect(page.locator("#relation-description-editor")).toHaveCount(0);
});

When("I open the relation's provenance", async ({ page }) => {
  await page.locator("#modal-component .relation-provenance-open").click();
  await drain(page);
  await expect(provenancePane(page)).toBeVisible();
  // The pane opens before its answer lands, the way the item's page does; the
  // rows are what the scenarios are about, so wait for them.
  await expect(
    provenancePane(page).locator(".provenance-line, .provenance-empty").first(),
  ).toBeVisible();
});

When("I close the relation's provenance", async ({ page }) => {
  await page.locator("#modal-component .relation-provenance-open").click();
  await drain(page);
  await expect(provenancePane(page)).toHaveCount(0);
});

// The server's own sentence, not a wording retyped in the client -- the same
// check the item's provenance page carries, and for the same reason.
Then("the relation provenance should carry a legend", async ({ page }) => {
  const legend = provenancePane(page).locator(".provenance-legend");
  await expect(legend).toBeVisible();
  await expect(legend).toContainText("caution runs from 1.00 to 0.00");
});

Then(
  "the relation provenance line {string} should be attributed {string}",
  async ({ page }, text: string, caution: string) => {
    // evaluateAll does not retry, so the rows have to be there before it runs --
    // the pane is up one render before its answer lands.
    await expect(provenancePane(page).locator(".provenance-line").first()).toBeVisible();
    const rows = await provenancePane(page).locator(".provenance-line").evaluateAll(
      (els: Element[]) =>
        els.map((row) => ({
          // The zero-width space that keeps an empty source line tall enough to
          // tint goes back out here, so a blank line reads as blank.
          text: (row.querySelector(".provenance-text") as HTMLElement).innerText
            .replace(/​/g, ""),
          caution: row.getAttribute("data-caution"),
          tint: getComputedStyle(row).backgroundColor,
        })),
    );
    const row = rows.find((r: any) => r.text === text);
    expect(row, `no line reading "${text}" in the pane`).toBeTruthy();
    expect(row.caution).toBe(caution);
    expect(row.tint, "an attributed line is drawn with a wash behind it")
      .not.toBe("rgba(0, 0, 0, 0)");
  },
);

Then("the relation provenance should say there is nothing to attribute", async ({ page }) => {
  await expect(provenancePane(page).locator(".provenance-empty"))
    .toContainText("nothing to attribute");
  await expect(provenancePane(page).locator(".provenance-line")).toHaveCount(0);
});
