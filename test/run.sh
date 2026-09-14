#!/usr/bin/env bash
# Everything that must pass before a push. None of it is optional, and none of
# it needs a Qobuz or Spotify account: the service clients are driven by a fake
# fetch, and the migration engine by fake services with the same interface.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> node --check on every source file"
for f in index.js lib/*.js public/*.js; do
  [ -e "$f" ] || continue
  node --check "$f"
done

echo "==> unit and API tests"
node --test test/unit/*.test.js

echo
echo "All checks passed."
