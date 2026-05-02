---
name: develop-e2e-tests
description: Gotchas and patterns for writing Rhizome e2e tests (Playwright + playwright-bdd)
---

# Developing e2e tests

The suite lives in `e2e/features/*.feature` (Gherkin) and `e2e/steps/*.ts`
(step definitions). `npm run e2e` builds a release bundle, spawns a JVM on
:3005 against `cometoid_test`, and runs Playwright.

If `.features-gen/` is stale or missing, run `npx bddgen` once before
`npm run e2e`.

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

### 2. After Enter, wait for `networkidle`

Pressing Enter to create a context fires an async API call. React then
re-renders: the lhs input unmounts and the rhs input mounts (empty). If the
test types the next text before that settles, the value lands on the
about-to-unmount lhs input, and the subsequent Enter resolves to the empty
rhs input — nothing gets created.

```ts
await target.press(key);
if (key === "Enter" || key === "Shift+Enter") {
  await page.waitForLoadState("networkidle");
}
```

### 3. `cljs-text-editor` runs its own keydown listener first

The search input is wrapped by `net.eighttrigrams.cljs-text-editor`, which
attaches a `keydown` listener via `addEventListener` directly on the input.
That fires at target phase, *before* React's root-level bubble delegate. For
unbound keys (plain Enter, no modifiers) the editor's handler is a no-op
(`:dont-prevent-default true`) and React's handler runs after — but if you
ever see a key fire twice or get swallowed, this is the order to remember.

### 4. A green run can lie

`/test/reset` clears `relations` and `items`. If reset is bypassed or fails,
the DB carries state across runs and tests can pass on stale data — e.g. a
"create Books" test that succeeds because Books was already there from a
previous session. When debugging, clear the DB manually:

```bash
PGPASSWORD=abcdef psql -h 127.0.0.1 -p 5437 -U daniel -d cometoid_test \
  -c 'DELETE FROM relations; DELETE FROM items;'
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
