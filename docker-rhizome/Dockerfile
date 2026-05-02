FROM clojure:temurin-21-tools-deps-alpine

ARG USER_UID=501
ARG USER_GID=20

RUN apk add --no-cache \
    bash \
    curl \
    git \
    github-cli \
    jq \
    make \
    nodejs \
    npm \
    openssh-client \
    postgresql-client \
    shadow \
    sudo \
    chromium \
    nss \
    freetype \
    harfbuzz \
    ca-certificates \
    ttf-freefont \
    font-noto-emoji \
    lsof \
    procps \
    gcompat

RUN curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install \
 && chmod +x install \
 && ./install --dir /usr/local/bin --static \
 && rm install

ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
ENV PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium
ENV PATH="/opt/java/openjdk/bin:${PATH}"
RUN printf 'export JAVA_HOME=/opt/java/openjdk\nexport PATH=$JAVA_HOME/bin:$PATH\n' > /etc/profile.d/java.sh

RUN npm install -g @anthropic-ai/claude-code \
 && mv /usr/local/bin/claude /usr/local/bin/claude-bin \
 && printf '#!/bin/sh\nexec /usr/local/bin/claude-bin --dangerously-skip-permissions "$@"\n' > /usr/local/bin/claude \
 && chmod +x /usr/local/bin/claude

RUN if ! getent group ${USER_GID} >/dev/null; then addgroup -g ${USER_GID} hostgrp; fi \
 && adduser -D -u ${USER_UID} -G $(getent group ${USER_GID} | cut -d: -f1) claude \
 && mkdir -p /home/claude/.claude /home/claude/.m2 /home/claude/.npm \
 && mkdir -p /workspace /workspace/.cpcache /workspace/.shadow-cljs /workspace/node_modules \
 && chown -R ${USER_UID}:${USER_GID} /home/claude /workspace

COPY --chown=${USER_UID}:${USER_GID} claude-config.json /home/claude/.claude.json

USER claude
ENV HOME=/home/claude
WORKDIR /workspace

ENTRYPOINT ["/bin/bash"]
