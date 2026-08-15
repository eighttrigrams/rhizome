# Two stages: `base` carries the common rhizome dev toolchain, and `box` is a
# plain dev shell (root) on top of it. `make box` builds `target: box` -- see
# docker-compose.yml.
#
# `base` also has a consumer outside this repo, which builds this stage on its
# own and tags the result (`FROM base` cannot cross files). Nothing here
# depends on that, but it does mean the vector-search machinery below -- the
# sqlite-vec install, VEC_PATH / VEC_URL, /etc/rhizome-use-ollama, and the
# socat bridge in entrypoint.sh -- is shared structure rather than box-only.
# Moving any of it up out of `base` would break that consumer silently, since
# WITH_VEC is a build arg to this stage and nothing downstream can restore it.
FROM clojure:temurin-21-tools-deps-bookworm-slim AS base

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    curl \
    git \
    imagemagick \
    jq \
    make \
    sqlite3 \
    ca-certificates \
    lsof \
    procps \
    socat \
    zstd \
 && rm -rf /var/lib/apt/lists/*

# Node 22 from NodeSource (bundles npm): bookworm ships Node 18, and Playwright
# refuses to run on anything below 20. 22 rather than 20 because the consumer
# built on top of this stage installs global npm packages that require it --
# below 22 npm only warns EBADENGINE and then silently skips linking the
# binary, so that build fails late and obscurely instead of here.
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

RUN curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install \
 && chmod +x install \
 && ./install --dir /usr/local/bin --static \
 && rm install

ARG SQLITE_VEC_VERSION=0.1.9
ARG WITH_VEC=0
RUN if [ "$WITH_VEC" = "1" ]; then \
      mkdir -p /usr/local/lib/sqlite-vec \
   && case "$(uname -m)" in \
        x86_64)  slug=linux-x86_64 ;; \
        aarch64) slug=linux-aarch64 ;; \
        *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;; \
      esac \
   && curl -fsSL "https://github.com/asg017/sqlite-vec/releases/download/v${SQLITE_VEC_VERSION}/sqlite-vec-${SQLITE_VEC_VERSION}-loadable-${slug}.tar.gz" \
      | tar -xz -C /tmp \
   && cp /tmp/vec0.so /usr/local/lib/sqlite-vec/vec0.so \
   && rm -f /tmp/vec0.so \
   && test -f /usr/local/lib/sqlite-vec/vec0.so; \
    else \
      echo "Skipping sqlite-vec install (WITH_VEC=0)"; \
    fi

# Flag the entrypoint reads to decide whether to wait for the Ollama sidecar
# and pull the embedding model on first run. Set in lockstep with the
# sqlite-vec install above so semsearch dependencies stay together.
RUN if [ "$WITH_VEC" = "1" ]; then echo "1" > /etc/rhizome-use-ollama; else echo "0" > /etc/rhizome-use-ollama; fi

# config.edn's :semsearch entries use aero #or [#env ...] so the same file
# works on host and in the container. Inside the container:
#   * the sqlite-vec .so lives at /usr/local/lib/sqlite-vec/vec0 (see the
#     WITH_VEC RUN block above)
#   * the ollama sidecar is reached via a socat bridge on 127.0.0.1:11437
#     (entrypoint.sh), kept off :11434 so a host-side ollama exposed into
#     this container's network namespace can't shadow the sidecar
# Set unconditionally -- harmless when :semsearch isn't in config.edn, and
# means we don't carry a second copy of the WITH_VEC conditional here.
ENV VEC_PATH=/usr/local/lib/sqlite-vec/vec0
ENV VEC_URL=http://127.0.0.1:11437

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

WORKDIR /workspace/rhizome
# Rhizome dev ports per ../README.md: PORT=3140 (JVM), SHADOW_PORT=9804 (shadow-cljs).
# EXPOSE is documentation only; actual publishing is via compose --service-ports.
EXPOSE 3140 9804
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]


# ---------------------------------------------------------------------------
# box: plain dev shell, runs as root, no browser stack.
# `make e2e` from inside `box` will fail by design with a directed message
# (see scripts/e2e.sh) -- it needs a browser, so run it on the host.
# ---------------------------------------------------------------------------
FROM base AS box
# Project git policy, and deliberately no identity: commits made in here are
# the user's own work and must be attributed to whoever made them, so
# user.name / user.email are left for git to resolve as it normally would.
# pull.rebase=false enforces the merge-not-rebase policy.
RUN printf '[pull]\n\trebase = false\n[init]\n\tdefaultBranch = main\n' > /root/.gitconfig
CMD ["bash"]
