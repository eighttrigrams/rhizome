import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

Given("I am on the app", async ({ page, request }) => {
  await request.post("/test/reset", { data: { reason: "e2e test reset" } });
  await page.goto("/");
  await page.locator("#main-layer").waitFor({ state: "attached" });
});

When("I press the {string} key", async ({ page }, key: string) => {
  const searchInput = page.locator("#search-input");
  const target = (await searchInput.count()) > 0 ? searchInput : page.locator("#main-layer");
  await target.press(key);
});

When("I type {string} in the search input", async ({ page }, text: string) => {
  const input = page.locator("#search-input");
  await input.waitFor({ state: "visible" });
  await expect(input).toHaveValue("");
  await input.fill(text);
});

Then("I should see {string} in the lhs", async ({ page }, text: string) => {
  await expect(page.locator("#lhs-component")).toContainText(text);
});

Then("I should see {string} in the rhs", async ({ page }, text: string) => {
  await expect(page.locator("#rhs-component")).toContainText(text);
});
