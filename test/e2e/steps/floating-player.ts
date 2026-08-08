import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";
import { settle } from "../settle";

const { When, Then } = createBdd();

// Mirrors ui.floating-player/corner-padding. A corner is 20px clear of both
// edges — the badges live in the corners too, and a player flush against the
// edge reads as an accident.
const CORNER_PADDING = 20;

// The node the player's iframe was, so a later step can ask whether it is still
// that same node. Module-level rather than a fixture: the suite runs one worker
// and every scenario that asks re-remembers first, so there is nothing here to
// carry between them.
let rememberedFrame: any = null;

// Setup over REST, and deliberately so: creating an item through the search
// input is a round trip whose late response overwrites whatever the next
// keypress produced, and two of them chained behind a context is one hop more
// than that handling reliably survives. POST /api/items takes the description
// straight, which is the half of display-youtube-video reachable from out here
// — the :youtube-video resource link cannot be set over the API at all.
When(
  "{string} holds an item {string} showing {string}",
  async ({ request }, contextTitle: string, title: string, url: string) => {
    const found = await request.get(`/api/items?q=${encodeURIComponent(contextTitle)}`);
    expect(found.ok(), `searching for "${contextTitle}" failed`).toBeTruthy();
    const context = (await found.json())
      .find((i: any) => i.title === contextTitle && i["is-context"]);
    expect(context, `no context titled "${contextTitle}"`).toBeTruthy();
    const resp = await request.post("/api/items", {
      data: {
        title,
        "context-ids": [context.id],
        description: `Notes on this one.\n\n${url}\n`,
        // The write gate wants a reason for every mutation, the same one the
        // description PUT next door hands it.
        reason: "e2e test setup",
      },
    });
    expect(resp.status(), await resp.text()).toBe(201);
  },
);

When("I click the video poster", async ({ page }) => {
  await page.locator("#lhs-component .video-poster").first().click();
  await settle(page);
});

When("I remember the player's iframe", async ({ page }) => {
  rememberedFrame = await page.locator("#floating-player-frame").elementHandle();
  expect(rememberedFrame, "there is no player to remember").toBeTruthy();
});

When("I close the player", async ({ page }) => {
  await page.locator(".floating-player-close").click();
  await settle(page);
});

When("I open the player's QR code", async ({ page }) => {
  await page.locator(".floating-player-qr").click();
  await expect(page.locator("#qr-overlay")).toBeVisible();
});

// Deliberately not the shared "I click {string} in the lhs": that one drains on
// networkidle, and with a video playing the network never goes idle. Same click,
// settled the way anything reachable with a player up has to be.
//
// Aimed at the context badge on the item's card, which is the way back to the
// whole from an item: selecting an item replaces the list it was picked from
// with its own related items, so a sibling cannot be reached from there.
When("I go back to {string} in the lhs", async ({ page }, text: string) => {
  await page.locator("#lhs-component .badge").filter({ hasText: text }).first().click();
  await settle(page);
  await expect(page.locator("#rhs-component li.item-card").first()).toBeVisible();
});

// Grab the strip, sweep across the video, let go.
//
// The first leg goes straight down into the box's own body, and that is the
// leg that matters: the iframe is under those coordinates when the move
// starts, so the pointer genuinely crosses the video, which is what the
// capture on the handle is for. Without it a move landing on a cross-origin
// iframe is delivered into that document and never arrives here, and the drag
// dies halfway.
//
// It has to be done on purpose rather than fallen into. The box follows the
// pointer, so a drag that only travels up or sideways keeps the pointer over
// the handle the whole way and never touches the iframe at all — it would pass
// with no capture in place. That was true by luck while the player opened in
// the top-left and every drag went down and right; from the bottom-left corner
// it is not.
async function dragInto(page: any, quadrant: string) {
  const [vertical, horizontal] = quadrant.split("-");
  const box = (await page.locator("#floating-player").boundingBox())!;
  const grip = (await page.locator(".floating-player-handle").boundingBox())!;
  const view = page.viewportSize()!;
  const startX = grip.x + grip.width / 2;
  const startY = grip.y + grip.height / 2;
  // The grab point keeps its offset into the box for the whole gesture, so aim
  // the pointer where it has to be for the box's *centre* to land well inside
  // the target quadrant — the centre is what decides the corner.
  const centreX = view.width * (horizontal === "left" ? 0.25 : 0.75);
  const centreY = view.height * (vertical === "top" ? 0.25 : 0.75);
  const targetX = centreX - box.width / 2 + (startX - box.x);
  const targetY = centreY - box.height / 2 + (startY - box.y);

  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(startX, startY + box.height / 2, { steps: 8 });
  await page.mouse.move(targetX, targetY, { steps: 12 });
  await page.mouse.up();
  await settle(page);
}

When("I drag the player into the {string} quadrant", async ({ page }, quadrant: string) => {
  await dragInto(page, quadrant);
});

// The same gesture, said as what it is for. The player opens over the bottom
// of the lhs, which is where the icon under a still sits, so a scenario that
// has to reach something under there moves it to the other side first —
// exactly as the owner would, and the reason it is draggable.
When("I move the player out of the way", async ({ page }) => {
  await dragInto(page, "bottom-right");
});

Then("the item should show a still for {string}", async ({ page }, id: string) => {
  const still = page.locator("#lhs-component .video-poster-still");
  await expect(still).toHaveCount(1);
  await expect(still).toHaveAttribute("src", `https://img.youtube.com/vi/${id}/hqdefault.jpg`);
});

Then("nothing should be playing in the item view", async ({ page }) => {
  await expect(page.locator("#lhs-component iframe")).toHaveCount(0);
});

Then("nothing should be playing", async ({ page }) => {
  await expect(page.locator("#floating-player")).toHaveCount(0);
});

Then("there should be exactly one player", async ({ page }) => {
  await expect(page.locator("#floating-player")).toHaveCount(1);
  await expect(page.locator("#floating-player-frame")).toHaveCount(1);
});

Then("the player should be playing {string}", async ({ page }, id: string) => {
  await expect(page.locator("#floating-player-frame"))
    .toHaveAttribute("src", `https://www.youtube.com/embed/${id}?autoplay=1`);
});

Then("the player should be asked to start on its own", async ({ page }) => {
  const frame = page.locator("#floating-player-frame");
  // Asked for in the URL...
  await expect(frame).toHaveAttribute("src", /[?&]autoplay=1/);
  // ...and granted to the frame. Without this the policy stops the video at the
  // boundary and the player comes up paused, which looks like a working player
  // until someone watches it.
  await expect(frame).toHaveAttribute("allow", /autoplay/);
});

Then("the player should be the very same iframe", async ({ page }) => {
  expect(rememberedFrame, "no iframe was remembered").toBeTruthy();
  const same = await page.evaluate(
    (before) => document.querySelector("#floating-player-frame") === before,
    rememberedFrame,
  );
  expect(same, "the player's iframe was replaced — a remount plays it from zero").toBe(true);
});

// Read off the composited page rather than off the style attribute: the corners
// are set as calc() against the viewport, so what the box declares and where it
// actually is are different claims, and only the second one is the feature.
// Polled, because settling into a corner is a 180ms transition by design.
Then("the player should be in the {string} corner", async ({ page }, corner: string) => {
  const where = async () => page.evaluate((pad) => {
    const r = document.querySelector("#floating-player")!.getBoundingClientRect();
    const near = (n: number) => Math.abs(n - pad) <= 2;
    const v = near(r.top) ? "top" : near(window.innerHeight - r.bottom) ? "bottom" : `y=${Math.round(r.top)}`;
    const h = near(r.left) ? "left" : near(window.innerWidth - r.right) ? "right" : `x=${Math.round(r.left)}`;
    return `${v}-${h}`;
  }, CORNER_PADDING);
  await expect.poll(where, {
    message: `the player never settled into the ${corner} corner`,
    timeout: 5000,
  }).toBe(corner);
});

Then("the player should not offer its QR code", async ({ page }) => {
  const icon = page.locator(".floating-player-qr");
  await expect(icon).toHaveCount(1);
  // Still on the page — the player is above the overlay on purpose — but not
  // something a click can reach while a code is already up.
  await expect(icon).toHaveCSS("pointer-events", "none");
});

Then("there should be exactly one QR overlay", async ({ page }) => {
  await expect(page.locator("#qr-overlay")).toHaveCount(1);
});
