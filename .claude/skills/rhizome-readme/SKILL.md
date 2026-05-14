---
name: rhizome-readme
description: Principles for structuring the Rhizome README
---

The first principle is that onboarding is as easy and simple as possible,
and as unintrusive as possible. When a user has Docker, this should be
thusly the best fit. 

All ports should be configurable at all times, to avoid annoyance on developers side.

Next, working alternatingly - not at the same time, but deciding on a case by case basis 
depending on the task at hand - between working inside or outside of a container, should
be seemlessly possible.

## Paths that should verifably work

Tests with Playwright (can be headless)
from the host system, and controlling another CMUX surface.

### 1 First docker box, then host, then docker yolo, then vector inside docker box

Assume
- Clojure, Babashka etc. are installed

Make sure
- No containers, no volumes exist which belong to Rhizome (delete them)
- node_modules on host does not exist (delete it)
- Configs are clean (run `make clean`)
- a `docker/token` exists

Steps
1. Developer installs via Docker, using default ports, using `make box`.
1. `make test` should work
1. Developer starts application inside container with `make start`
1. App should be reachable at 3006 (verify with Playwright), should show seed data
1. Developer exists container and switches to host system
1. Developer runs `npm i`
1. `make test` should work
1. Developer starts the app from outside the container with `make start`
1. App should be reachable at 3006 (verify with Playwright), should show seed data
1. Developer stops the app with `make stop`
1. Developer runs `make yolo`
1. Inside the container, run `claude` (bypass permissions mode is ok *inside* the container)
1. Claude should indicate that it is logged in (try `/status`)
1. `/mcp` should list `playwright` as connected
1. Prompt "start the app and take a screenshot. call it abc1.png"
1. Verify on the host system that the screenshot shows Rhizome seed data with Contexts on the LHS and Items on the RHS
1. Exit container
1. `make clean` on host
1. `make box WITH_VEC=1`
1. `make onboard`
1. `make start`
1. `make backfill-embeddings`
1. On host, system, filtering "Articles" by vector search, as shown in the README, should work
