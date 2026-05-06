---
name: develop-e2e-tests
description: Gotchas and patterns for writing Rhizome e2e tests (Playwright + playwright-bdd)
---

# Developing e2e tests

The suite lives in `test/e2e/features/*.feature` (Gherkin) and `test/e2e/steps/*.ts`
(step definitions). `make e2e` (or `npm run e2e`) builds a release bundle,
spawns a JVM on :3005 against `./rhizome-e2e.db` (SQLite), and runs Playwright.
The npm script runs `bddgen` first, so generated specs under
`test/.features-gen/` are kept fresh automatically. `make e2e HEADED=1` shows
the browser.

## Gotchas

### 1. Don't use `.first()` on `"#search-input, #main-layer"`

Both selectors match in DOM order, and `#main-layer` is the ancestor — so
`.first()` returns it. Pressing Enter on `#main-layer` never reaches the
input's handler, so context/item creation silently doesn't happen.

Use this instead:

```ts
const searchInput = page.locator("#search-input");
const target = (await searchInput.count()) > 0
  ? searchInput
  : page.locator("#main-layer");
await target.press(key);
```

### 2. After Enter, wait for the lhs↔rhs input swap — `networkidle` alone is not enough

Pressing Enter to create a context fires an async API call. React then
re-renders: the lhs input unmounts and the rhs input mounts (empty). Both
panels render `[:input#search-input ...]` — *same* `id`, *different* DOM
elements. If the test types before the swap commits, the value lands on the
about-to-unmount lhs input; the subsequent Enter resolves to the empty rhs
input — nothing gets created (or the server gets an empty title and falls
back to today's date as the title, which is the easy way to spot this).

`networkidle` is a network-layer signal — it fires when the API response
lands. Reagent flushes its render via `requestAnimationFrame`, which can lag
behind. Worse, `expect(input).toHaveValue("")` *passes* for the lhs in this
window, because the Enter handler clears the lhs's value before the unmount
commits — so a naive type step accepts the wrong element as "fresh".

The robust pattern is to capture the input element handle *before* the
keypress and wait until the DOM's `#search-input` is no longer that element
(or no longer present). That's the marker that Reagent has committed:

```ts
const searchInput = page.locator("#search-input");
const target = (await searchInput.count()) > 0 ? searchInput : page.locator("#main-layer");
const beforeHandle = (await searchInput.count()) > 0
  ? await searchInput.elementHandle()
  : null;
await target.press(key);
await page.waitForLoadState("networkidle");
if (beforeHandle) {
  await page.waitForFunction(
    (before) => document.querySelector("#search-input") !== before,
    beforeHandle,
    { timeout: 5000 },
  );
}
```

Apply this on **every** keypress, not just Enter — `c` and `i` both kick off
state changes whose responses pass through `reset-state!` and can clobber
later state if the test moves on too soon.

### 3. `cljs-text-editor` runs its own keydown listener first

The search input is wrapped by `net.eighttrigrams.cljs-text-editor`, which
attaches a `keydown` listener via `addEventListener` directly on the input.
That fires at target phase, *before* React's root-level bubble delegate. For
unbound keys (plain Enter, no modifiers) the editor's handler is a no-op
(`:dont-prevent-default true`) and React's handler runs after — but if you
ever see a key fire twice or get swallowed, this is the order to remember.

### 4. `fetch-and-reset!` is a state clobber — drain it before the next action

Most action keypresses (`c`, Enter that creates, search-on-type, Escape,
etc.) flow through `ui.actions.common/fetch-and-reset!`. Its response calls
`reset!` on `*state` with `(state-snapshot-captured-at-call-time + response)`
— which silently wipes any concurrent state changes that happened between
the call and the response. Specific cases the existing steps already
handle:

- The component-mount `(actions/fetch! *state)` in `ui.main` fires at page
  load; if a key is pressed before its response, the keypress's state
  change is clobbered.
- The 180ms debounced `save-input` (from the input's `on-change`) calls
  `search!` → `fetch-and-reset!`; if Enter lands first, the debounce's
  late response can clobber the state Enter just produced.

The existing step definitions in `test/e2e/steps/contexts.ts` encode the
corresponding waits (`waitForLoadState("networkidle")` after page load and
keypresses; `waitForTimeout(220)` past the debounce window before draining)
with comments explaining each. Treat those waits as **load-bearing**: when
copying a step or writing a new one, preserve them. Anytime you add a step
that triggers a fresh `fetch-and-reset!` path (a new keybinding, a new
action), assume the same drain pattern applies until proven otherwise.

### 5. A green run can lie

`/test/reset` clears `relations` and `items`. If reset is bypassed or fails,
the DB carries state across runs and tests can pass on stale data — e.g. a
"create Books" test that succeeds because Books was already there from a
previous session. When debugging, clear the DB manually:

```bash
sqlite3 ./rhizome-e2e.db 'DELETE FROM relations; DELETE FROM items;'
```

…and re-run a single scenario in isolation:

```bash
npx playwright test --grep "scenario name"
```

Also, re-running a single or multiple tests a couple of times (3) in a row 
can often be a good idea to gain trust in the test or suite.

## Inspecting failures

Playwright drops a trace and `error-context.md` per failed scenario under
`test-results/<scenario-dir>/`. The error-context includes the page YAML
snapshot at moment of failure — much faster than spelunking the trace zip.
For the trace itself:

```bash
unzip -o test-results/<dir>/trace.zip -d /tmp/trace
# screenshots are in /tmp/trace/resources/*.jpeg
```

Re-run with `--trace=on` to force trace capture even on passing runs.
