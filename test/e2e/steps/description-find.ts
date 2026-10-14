import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// The find box is CodeMirror's own search panel; ui.codemirror/find-keys only
// gives it the owner's chords. What these steps read back is the panel in the
// DOM and the editor's selection, off the view ui.codemirror/create-editor
// leaves at element.__codemirror.

async function drain(page: any) {
  await page.waitForTimeout(100);
  await page.waitForLoadState("networkidle");
  await page.evaluate(
    () => new Promise<void>((r) =>
      requestAnimationFrame(() => requestAnimationFrame(() => r())),
    ),
  );
}

const panel = (page: any) => page.locator("#description-editor .cm-search");

When("I open the description", async ({ page }) => {
  // `d` is bound on #main-layer, which an open search input would not let it
  // reach -- see "I open the edit modal" in edit-modal.ts.
  if ((await page.locator("#search-input").count()) > 0) {
    await page.locator("#search-input").press("Escape");
    await drain(page);
  }
  await page.locator("#main-layer").press("d");
  await expect(page.locator("#description-editor .cm-content")).toBeVisible();
});

// Wherever the focus is -- the text, or the box's own field -- since the chords
// have to work from both.
When("I press {string} in the description", async ({ page }, keys: string) => {
  await page.keyboard.press(keys);
  await drain(page);
});

// Typed, not filled: the panel commits its query on keyup and change, and
// fill() fires neither, which would leave ⌘. with nothing to look for.
When("I type {string} in the find box", async ({ page }, text: string) => {
  await panel(page).locator("input[name=search]").pressSequentially(text);
});

Then("the find box should be open", async ({ page }) => {
  await expect(panel(page)).toBeVisible();
  await expect(panel(page).locator("input[name=search]")).toBeFocused();
});

Then("the find box should be closed", async ({ page }) => {
  await expect(panel(page)).toHaveCount(0);
});

Then("the match on line {int} should be selected", async ({ page }, line: number) => {
  const sel = await page.evaluate(() => {
    const view = (document.getElementById("description-editor") as any).__codemirror;
    const main = view.state.selection.main;
    return {
      text: view.state.sliceDoc(main.from, main.to),
      line: view.state.doc.lineAt(main.from).number,
    };
  });
  expect(sel).toEqual({ text: "genes", line });
});

Then("the description should still be open", async ({ page }) => {
  await expect(page.locator("#description-editor")).toBeVisible();
});

Then("the description should be closed", async ({ page }) => {
  await expect(page.locator("#description-editor")).toHaveCount(0);
});
