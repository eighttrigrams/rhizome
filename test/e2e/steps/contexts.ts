import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

Given("I am on the app", async ({ page, request }) => {
  await request.post("/test/reset", { data: { reason: "e2e test reset" } });
  await page.goto("/");
  await page.locator("#main-layer").waitFor({ state: "attached" });
  // The component-mount fetch! in ui.main fires a list-resources request whose
  // response calls reset-state!, which replaces *state with a value computed from
  // the snapshot taken at fetch start. If the user presses a key before that
  // response lands, the keypress's state change (e.g. :active-search :contexts)
  // is wiped when the late response resets the atom. Wait for it to settle.
  await page.waitForLoadState("networkidle");
});

When("I press the {string} key", async ({ page }, key: string) => {
  const searchInput = page.locator("#search-input");
  const target = (await searchInput.count()) > 0 ? searchInput : page.locator("#main-layer");
  // Capture the current input element (if any) so we can detect when Reagent
  // commits the lhs↔rhs swap that follows the keypress. Both panels render
  // [:input#search-input ...], so id-only selectors can't tell them apart.
  const beforeHandle = (await searchInput.count()) > 0
    ? await searchInput.elementHandle()
    : null;
  await target.press(key);
  // Most key presses trigger a fetch-and-reset! whose response calls
  // reset-state! using a state snapshot captured at the call site. Drain
  // in-flight requests so a late response can't clobber subsequent state.
  // Brief tick first: fetch-and-reset! schedules its XHR via a cljs go-block
  // (microtask), so the request hasn't yet hit the wire when target.press
  // resolves. Without this nudge, networkidle can fire before the XHR is
  // observable, and a subsequent press races against the in-flight response
  // — producing only one effective "cycle" out of two, etc.
  await page.waitForTimeout(100);
  await page.waitForLoadState("networkidle");
  // networkidle is a network-layer signal; Reagent's render that unmounts
  // the old input (lhs) and mounts the new one (rhs) flushes via rAF and
  // can lag behind. Wait until the input element seen at press-time is no
  // longer the one in the DOM — that's our marker that Reagent has committed.
  if (beforeHandle) {
    await page.waitForFunction(
      (before) => {
        const cur = document.querySelector("#search-input");
        return cur !== before;
      },
      beforeHandle,
      { timeout: 5000 },
    );
  }
});

When("I type {string} in the search input", async ({ page }, text: string) => {
  const input = page.locator("#search-input");
  await input.waitFor({ state: "visible" });
  await expect(input).toHaveValue("");
  await input.fill(text);
  // The input's on-change schedules save-input on a 180ms debounce, which
  // then fires its own list-resources via fetch-and-reset! — capturing
  // state at fire time and reset!-ing on response. If the next step (e.g.
  // Enter) lands first, its state changes get clobbered by the late
  // debounced response. Wait past the debounce window, then drain.
  await page.waitForTimeout(220);
  await page.waitForLoadState("networkidle");
});

Then("I should see {string} in the lhs", async ({ page }, text: string) => {
  await expect(page.locator("#lhs-component")).toContainText(text);
});

Then("I should see {string} in the rhs", async ({ page }, text: string) => {
  await expect(page.locator("#rhs-component")).toContainText(text);
});

Then("I should not see {string} in the lhs", async ({ page }, text: string) => {
  await expect(page.locator("#lhs-component")).not.toContainText(text);
});

Then("I should not see {string} in the rhs", async ({ page }, text: string) => {
  await expect(page.locator("#rhs-component")).not.toContainText(text);
});

Then("I should see the search input", async ({ page }) => {
  await expect(page.locator("#search-input")).toBeVisible();
});

When("I click {string} in the lhs", async ({ page }, text: string) => {
  // Secondary-context list items, "Invert", and "No secondary contexts" are
  // plain spans/li with on-click handlers under #lhs-component. Click the
  // first matching label.
  await page.locator("#lhs-component").getByText(text, { exact: false }).first().click();
  // The handler calls change-secondary-contexts-* via fetch-and-reset!,
  // same drain pattern as a key press: settle the network, then give Reagent
  // a frame to commit the render. The grace tick before networkidle is
  // load-bearing — fetch-and-reset! schedules its XHR via a cljs go-block
  // (microtask), so networkidle can fire before the XHR is even on the
  // wire. A subsequent click would then race the in-flight response and
  // its late reset-state! can clobber the second click's swap!.
  await page.waitForTimeout(100);
  await page.waitForLoadState("networkidle");
  await page.evaluate(
    () => new Promise<void>((r) =>
      requestAnimationFrame(() => requestAnimationFrame(() => r())),
    ),
  );
});
