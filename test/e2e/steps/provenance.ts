import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { When, Then } = createBdd();

// Same pattern as the other step files: fetch-and-reset! schedules its XHR from
// a go-block, so give it a tick to reach the wire, settle the network, and let
// Reagent commit (it flushes on requestAnimationFrame, which lags the network
// layer).
async function drain(page: any) {
  await page.waitForTimeout(100);
  await page.waitForLoadState("networkidle");
  await page.evaluate(
    () => new Promise<void>((r) =>
      requestAnimationFrame(() => requestAnimationFrame(() => r())),
    ),
  );
}

// Gherkin has no way to write a newline inside a quoted string, and the
// trailing one is the whole point of these fixtures (see the Background), so
// the feature file spells them "\n" and they are turned into real newlines
// here rather than being quietly dropped.
function unescape(text: string): string {
  return text.replace(/\\n/g, "\n");
}

async function itemNamed(request: any, title: string): Promise<any> {
  const found = await request.get(`/api/items?q=${encodeURIComponent(title)}`);
  expect(found.ok(), `searching for "${title}" failed`).toBeTruthy();
  const hit = (await found.json()).find((i: any) => i.title === title);
  expect(hit, `no item titled "${title}"`).toBeTruthy();
  // The search hit is a listing row; caution rides on the single-item read.
  const resp = await request.get(`/api/items/${hit.id}`);
  expect(resp.ok(), `fetching item ${hit.id} failed`).toBeTruthy();
  return await resp.json();
}

// What the page drew, read back off the DOM: one entry per row, in order.
async function rowsOnScreen(page: any) {
  return await page.locator(".provenance-line").evaluateAll((rows: Element[]) =>
    rows.map((row) => ({
      lineno: (row.querySelector(".provenance-lineno") as HTMLElement).innerText.trim(),
      // The number is printed once, at the head of a range; every other row
      // leaves this column empty, which is what makes the visible marks
      // comparable with the API's ranges one for one.
      mark: (row.querySelector(".provenance-caution") as HTMLElement).innerText.trim(),
      caution: row.getAttribute("data-caution"),
      // The zero-width space that keeps an empty source line tall enough to
      // tint goes back out here, so a blank line reads as blank.
      text: (row.querySelector(".provenance-text") as HTMLElement).innerText.replace(/​/g, ""),
      tint: getComputedStyle(row).backgroundColor,
    })),
  );
}

When(
  "{string} holds an item {string} described as {string}",
  async ({ request }, contextTitle: string, title: string, description: string) => {
    const found = await request.get(`/api/items?q=${encodeURIComponent(contextTitle)}`);
    expect(found.ok(), `searching for "${contextTitle}" failed`).toBeTruthy();
    const context = (await found.json())
      .find((i: any) => i.title === contextTitle && i["is-context"]);
    expect(context, `no context titled "${contextTitle}"`).toBeTruthy();
    const resp = await request.post("/api/items", {
      data: {
        title,
        "context-ids": [context.id],
        description: unescape(description),
        reason: "e2e test setup",
      },
    });
    expect(resp.status(), await resp.text()).toBe(201);
  },
);

// The one write in this feature that is NOT over REST, and it has to be: REST
// stamps every write "api", so nothing reachable from out there can produce a
// line that counts as the owner's. This goes through the description modal --
// `d` to open, Alt+9 to save -- which is the path that stamps "app", and which
// is therefore the only way to set up a description with both sides in it.
//
// The text is put in through CodeMirror's own API rather than typed. It is the
// same value either way: the modal reads what it saves off the view attached at
// element.__codemirror (ui.codemirror/create-editor puts it there, and
// ui.modals/get-current-description reads it back from exactly that). Typing it
// would additionally be exercising this project's custom CodeMirror keymap,
// which is not what the scenario is about.
When(
  "the owner himself adds {string} to the description",
  async ({ page }, line: string) => {
    await page.locator("#main-layer").press("d");
    await expect(page.locator("#description-editor")).toBeVisible();
    const wrote = await page.evaluate((text) => {
      const el = document.getElementById("description-editor") as any;
      const view = el && el.__codemirror;
      if (!view) return null;
      const before = view.state.doc.toString();
      // Ahead of the trailing empty line rather than after it, so the body
      // still ends in a newline once he has had his say.
      const next = before.endsWith("\n") ? `${before}${text}\n` : `${before}\n${text}\n`;
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: next } });
      return next;
    }, line);
    expect(wrote, "the description editor was not mounted").toBeTruthy();
    await page.locator("#description-editor").press("Alt+Digit9");
    await drain(page);
    // The modal stays open across a save on purpose; the save callback resets
    // the editor's baseline, so this Escape closes rather than raising the
    // discard dialog.
    await page.locator("#description-editor").press("Escape");
    await drain(page);
    await expect(page.locator("#description-editor")).toHaveCount(0);
  },
);

When("I open the provenance page", async ({ page }) => {
  await page.locator(".provenance-open").click();
  await expect(page.locator("#provenance-page")).toBeVisible();
  await drain(page);
  // The page opens before its answer lands, the way the config and diff pages
  // do; the rows are what the scenarios are about, so wait for them.
  await expect(page.locator(".provenance-line").first()).toBeVisible();
});

When("I close the provenance page", async ({ page }) => {
  await page.locator("#provenance-page .config-close").click();
  await drain(page);
});

When("I step the version bar back one version", async ({ page }) => {
  await page.locator(".version-bar-group button", { hasText: "←" }).first().click();
  await drain(page);
});

Then("the version bar should not be showing the current version", async ({ page }) => {
  await expect(page.locator(".version-bar")).not.toContainText("(current)");
});

Then("the provenance page should be gone", async ({ page }) => {
  await expect(page.locator("#provenance-page")).toHaveCount(0);
});

// The check the feature exists for: everything drawn, against the ranges the
// server answers with for the same item, fetched independently over REST.
Then(
  "the provenance page should agree with the API about {string}",
  async ({ page, request }, title: string) => {
    const item = await itemNamed(request, title);
    const ranges = item.caution.ranges;
    expect(ranges.length, "the API returned no ranges to check against").toBeGreaterThan(0);

    const rows = await rowsOnScreen(page);

    // One row per source line, split the way the library counted -- JS `split`
    // keeps trailing empty fields, as `#"\n" -1` does on the other side.
    const lines = item.description.split("\n");
    expect(rows.map((r: any) => r.text)).toEqual(lines);
    expect(rows.map((r: any) => r.lineno)).toEqual(lines.map((_: string, i: number) => String(i + 1)));

    // Every line carries the caution of the range that covers it.
    const expected: (string | null)[] = lines.map(() => null);
    for (const { from, to, caution } of ranges) {
      for (let n = from; n <= to; n++) expected[n - 1] = caution.toFixed(2);
    }
    expect(rows.map((r: any) => r.caution)).toEqual(expected);

    // And the marks actually printed on the page are the range heads, one for
    // one with the API's ranges, in order.
    const marked = rows
      .map((r: any, i: number) => ({ line: i + 1, mark: r.mark }))
      .filter((r: any) => r.mark !== "");
    expect(marked).toEqual(
      ranges.map((r: any) => ({ line: r.from, mark: r.caution.toFixed(2) })),
    );
  },
);

Then(
  "the provenance page should have one row per line of {string}",
  async ({ page, request }, title: string) => {
    const item = await itemNamed(request, title);
    expect(
      item.description.endsWith("\n"),
      "this fixture must end in a newline or it cannot catch the missing last row",
    ).toBe(true);
    await expect(page.locator(".provenance-line")).toHaveCount(item.description.split("\n").length);
  },
);

Then(
  "the page should carry the API's own legend for {string}",
  async ({ page, request }, title: string) => {
    const item = await itemNamed(request, title);
    const legend = item.caution.legend;
    expect(legend, "the API served no legend").toBeTruthy();
    await expect(page.locator(".provenance-legend")).toHaveText(legend);
  },
);

Then(
  "the line reading {string} should be attributed {string}",
  async ({ page }, text: string, caution: string) => {
    const rows = await rowsOnScreen(page);
    const row = rows.find((r: any) => r.text === text);
    expect(row, `no line reading "${text}" on the page`).toBeTruthy();
    expect(row.caution).toBe(caution);
    expect(row.tint, "an attributed line is drawn with a wash behind it")
      .not.toBe("rgba(0, 0, 0, 0)");
  },
);

Then("those two lines should not be tinted alike", async ({ page }) => {
  const rows = await rowsOnScreen(page);
  const tints = new Set(
    rows.filter((r: any) => r.caution !== null).map((r: any) => r.tint),
  );
  expect(
    tints.size,
    "every line came out the same colour — the spectrum is not being drawn",
  ).toBeGreaterThan(1);
});

Then("the provenance page should show the line {string}", async ({ page }, text: string) => {
  const rows = await rowsOnScreen(page);
  expect(rows.map((r: any) => r.text)).toContain(text);
});

Then("the provenance page should not show the line {string}", async ({ page }, text: string) => {
  const rows = await rowsOnScreen(page);
  expect(rows.map((r: any) => r.text)).not.toContain(text);
});
