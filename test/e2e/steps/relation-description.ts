import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// Every /ui command goes through one POST, with the command's name in the
// transit envelope's `fn`. Watching that is how a scenario can say a fetch did
// NOT happen -- which for this feature is the assertion that matters: the text
// is lazy, and "lazy" is a claim about requests that were never made.
let uiCalls: string[] = [];
const watched = new WeakSet<object>();

async function drain(page: any) {
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

When("I watch the calls to the server", async ({ page }) => {
  uiCalls = [];
  if (!watched.has(page)) {
    watched.add(page);
    page.on("request", (req: any) => {
      if (req.method() !== "POST" || !req.url().endsWith("/ui")) return;
      const body = req.postData();
      if (!body) return;
      try {
        uiCalls.push(JSON.parse(body).fn);
      } catch {
        /* not our envelope */
      }
    });
  }
});

Then("no relation text should have been fetched", async () => {
  expect(uiCalls.filter((f) => f === "fetch-relation-description")).toEqual([]);
});

Then("the relation text should have been fetched once", async () => {
  expect(uiCalls.filter((f) => f === "fetch-relation-description")).toHaveLength(1);
});

// The field is a CodeMirror editor, and it is mounted only once the fetch that
// opened with the modal has landed — so waiting for it is also where a modal that
// never loaded its text would fail.
//
// The text goes in through CodeMirror's own API rather than being typed, the way
// the description modal's does in provenance.ts and for its reason: the modal
// reads what it saves off the view attached at element.__codemirror, so this is
// the same value either way, and typing it would additionally be exercising this
// project's custom keymap.
async function editorText(page: any): Promise<string | null> {
  return await page.evaluate(() => {
    const el = document.getElementById("relation-description-editor") as any;
    const view = el && el.__codemirror;
    return view ? view.state.doc.toString() : null;
  });
}

When("I type {string} into the relation text", async ({ page }, text: string) => {
  await expect(page.locator("#relation-description-editor")).toBeVisible();
  const wrote = await page.evaluate((next: string) => {
    const el = document.getElementById("relation-description-editor") as any;
    const view = el && el.__codemirror;
    if (!view) return false;
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: next } });
    return true;
  }, text);
  expect(wrote, "the relation's editor was not mounted").toBe(true);
});

Then("the relation text should read {string}", async ({ page }, text: string) => {
  await expect(page.locator("#relation-description-editor")).toBeVisible();
  await expect.poll(() => editorText(page)).toBe(text);
});

// Both hover handlers — the card's and the strip's — bail while :loading is
// set, and :loading is cleared by save-input-debounced!, a 500ms debounce that
// starts when the last response lands (ui.actions.common). networkidle is a
// network signal and says nothing about it, so this waits the debounce out.
// Without it the pointer arrives during the window in which hovering is
// deliberately inert, and the lhs never changes.
async function settleLoading(page: any) {
  await page.waitForTimeout(600);
}

When("I hover the relation strip on {string}", async ({ page }, title: string) => {
  await settleLoading(page);
  await card(page, title).first().locator(".relation-annotation").hover();
  await drain(page);
});

// The card's own hover sets the item preview; the strip's overrules it. Aiming
// at the title is what makes this a move *off* the strip and not a second hover
// on it -- and the title rather than .item-card-inner, which collapses to no
// height on a card with no image (its one in-flow child is the picture, and
// .item-card-inner-right is positioned absolutely) and so is not a thing a
// pointer can be over.
When("I hover the body of the card {string}", async ({ page }, title: string) => {
  await settleLoading(page);
  await card(page, title).first().locator("span.title").hover();
  // The strip's mouse-leave clears the preview on a 300ms grace, the same one
  // the card's leave uses, so this waits past it rather than racing it.
  await page.waitForTimeout(400);
  await drain(page);
});

Then("the lhs should show the relation text {string}", async ({ page }, text: string) => {
  await expect(page.locator("#lhs-component .relation-preview-caption")).toBeVisible();
  await expect(page.locator("#lhs-component .description")).toContainText(text);
});

Then("the lhs should show no relation text", async ({ page }) => {
  await expect(page.locator("#lhs-component .relation-preview-caption")).toHaveCount(0);
});

Then("the lhs should say the relation has no text yet", async ({ page }) => {
  await expect(page.locator("#lhs-component .relation-preview-note"))
    .toContainText("Nothing written on this relation yet");
});
