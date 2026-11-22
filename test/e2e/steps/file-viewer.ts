import * as fs from "fs";
import * as path from "path";
import { expect } from "@playwright/test";
import { createBdd } from "playwright-bdd";

const { Given, When, Then } = createBdd();

// What /files/ answered in this scenario, by decoded file name. Taken off
// the responses rather than routed, so what is checked is the server's own
// answer (et.rz.server.files), Range and all.
const answers = new WeakMap<any, { file: string; status: number; type: string; range?: string }[]>();

function filesUrl(file: string) {
  return `/files/${encodeURIComponent(file)}`;
}

When("I watch the files fetched", async ({ page }) => {
  const seen: { file: string; status: number; type: string; range?: string }[] = [];
  answers.set(page, seen);
  page.on("response", (res: any) => {
    const url = new URL(res.url());
    if (!url.pathname.startsWith("/files/")) return;
    const headers = res.headers();
    seen.push({
      file: decodeURIComponent(url.pathname.slice("/files/".length)),
      status: res.status(),
      type: headers["content-type"],
      range: headers["content-range"],
    });
  });
});

Then("the viewer should show the pdf {string}", async ({ page }, file: string) => {
  await expect(page.locator("#image-viewer iframe.image-viewer-document")).toHaveAttribute("src", filesUrl(file));
});

Then("the viewer should show the audio {string}", async ({ page }, file: string) => {
  await expect(page.locator("#image-viewer audio.image-viewer-audio")).toHaveAttribute("src", filesUrl(file));
});

Then("the viewer should show the video {string}", async ({ page }, file: string) => {
  await expect(page.locator("#image-viewer video.image-viewer-video")).toHaveAttribute("src", filesUrl(file));
});

Then("{string} should have come as {string}", async ({ page }, file: string, type: string) => {
  await expect
    .poll(() => answers.get(page)!.filter((a) => a.file === file && a.status === 200).map((a) => a.type))
    .toContain(type);
});

Then("{string} should have been fetched in a range", async ({ page }, file: string) => {
  await expect
    .poll(() => answers.get(page)!.filter((a) => a.file === file && a.status === 206).length)
    .toBeGreaterThan(0);
  const answer = answers.get(page)!.find((a) => a.file === file && a.status === 206)!;
  expect(answer.range).toMatch(/^bytes \d+-\d+\/\d+$/);
});

const PLAYER = "#image-viewer .image-viewer-media";

// Rounded to the second: the mp3 is 1.04s, the mp4 1.4s.
Then("the player should be ready, {int} second(s) long", async ({ page }, seconds: number) => {
  await expect
    .poll(() => page.locator(PLAYER).evaluate((m: HTMLMediaElement) => m.readyState >= 1 && Math.floor(m.duration)))
    .toBe(seconds);
  await expect(page.locator(PLAYER)).toBeVisible();
});

Then("the video should be {int} by {int}", async ({ page }, w: number, h: number) => {
  expect(await page.locator(PLAYER).evaluate((v: HTMLVideoElement) => [v.videoWidth, v.videoHeight])).toEqual([w, h]);
});

// Seeking is what needs Range: without it the element knows no seekable
// range and stays where it is.
When("I seek the player to {float} seconds", async ({ page }, t: number) => {
  await page.locator(PLAYER).evaluate(
    (m: HTMLMediaElement, t: number) =>
      new Promise<void>((resolve) => {
        m.addEventListener("seeked", () => resolve(), { once: true });
        m.currentTime = t;
      }),
    t,
  );
});

Then("the player should be at {float} seconds", async ({ page }, t: number) => {
  expect(await page.locator(PLAYER).evaluate((m: HTMLMediaElement) => m.currentTime)).toBeCloseTo(t, 1);
});

Then("the viewer should say {string}", async ({ page }, text: string) => {
  await expect(page.locator("#image-viewer .image-viewer-status")).toContainText(text);
});

// The dev and e2e :docs folder, where the Background's fixture step put it.
Given("the file {string} is not on the server", async ({}, file: string) => {
  fs.rmSync(path.resolve(__dirname, "../../../files/Documents/Tracked", file), { force: true });
});

Then("the viewer should show no pdf", async ({ page }) => {
  await expect(page.locator("#image-viewer iframe")).toHaveCount(0);
});

Then("the viewer should offer {string} to download", async ({ page }, file: string) => {
  await expect(page.locator("#image-viewer .image-viewer-unshown a")).toHaveAttribute(
    "href",
    `${filesUrl(file)}?download`,
  );
});

// The download as the browser takes it: the name it would save under comes
// from the answer's Content-Disposition (and the link's download attribute).
let downloaded: Promise<any> | null = null;

When("I click the download button", async ({ page }) => {
  downloaded = page.waitForEvent("download");
  await page.locator("#image-viewer .image-viewer-download").click();
});

Then("{string} should have been downloaded", async ({}, file: string) => {
  const download = await downloaded!;
  expect(download.suggestedFilename()).toBe(file);
  expect(await download.failure()).toBeNull();
});

// As "at image n of m" in image-viewer.ts, but for any kind: that one also
// notes the <img> shown, which a sound has none of.
Then(
  "the viewer should be at file {int} of {int}, {string}",
  async ({ page }, n: number, of: number, title: string) => {
    await expect(page.locator(".image-viewer-position")).toHaveText(`${n} / ${of}`);
    await expect(page.locator(".image-viewer-title")).toHaveText(title);
  },
);
