# Node 22, not 20. Roon discovery and the MOO session need the global
# WebSocket, which is only there from 22 — and Node 20 is out of support
# anyway. A Roon scan on Node 20 would fail with "this Node build has no
# WebSocket", which is at least loud, but there is no reason to ship it.
FROM node:22-bookworm-slim

# better-sqlite3 has no prebuilt binary for every platform this may be built
# on, so the toolchain has to be here. It is the only native dependency.
RUN apt-get update && apt-get install -y python3 make g++ && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package*.json ./
ENV NPM_CONFIG_UPDATE_NOTIFIER=false
RUN npm install --omit=dev --no-audit --no-fund --loglevel=error

COPY . .

RUN mkdir -p /app/data

# Holds the SQLite database: the two services' tokens, and the match cache that
# makes a re-run of the same migration cheap. Losing it means signing in again
# and paying for every lookup a second time.
VOLUME /app/data

EXPOSE 3380

ENV PORT=3380
ENV DOCKER=1

CMD ["npm","start"]
