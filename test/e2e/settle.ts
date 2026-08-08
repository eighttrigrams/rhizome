// Deliberately without the waitForLoadState("networkidle") that every other
// step file drains on. Once a video is playing the embed keeps talking to
// YouTube for as long as it is mounted, so the network never goes idle and the
// wait runs to the test timeout — measured: the first version of the QR steps
// timed out in exactly the step that pressed a key with the player up. That is
// no longer only the QR's problem: the floating player is mounted for as long
// as a video plays, across every navigation the suite makes afterwards, so any
// step reachable with a video running has to settle this way instead.
//
// Give the go-block its tick, let Reagent commit (it flushes on
// requestAnimationFrame, which lags the network layer), and lean on the
// retrying assertions for the rest.
export async function settle(page: any) {
  await page.waitForTimeout(150);
  await page.evaluate(
    () => new Promise<void>((r) =>
      requestAnimationFrame(() => requestAnimationFrame(() => r())),
    ),
  );
}
