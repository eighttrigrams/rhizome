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

### First docker, then host

1. Developer installs via Docker, using default ports, using `make box`.
2. Developer starts application inside container with `make stop`
3. App should be reachable at 3006 (verify with Playwright), should show seed data
4. Developer stop the application with `make stop`
5. Developer exists container and switches to host system
6. Developer starts the app from outside the container with `make start`
7. App should be reachable at 3006 (verify with Playwright), should show seed data
