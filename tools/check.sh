#!/usr/bin/env bash
# Everything that must pass before a push, in one place. None of it needs a
# Qobuz account: the client is driven by a fake HTTP layer and the
# engines by fake services. The APK build itself is separate — see CLAUDE.md.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> the page parses and names nothing that does not exist"
for f in public/*.js; do node --check "$f"; done
npx --yes eslint@9 --config tools/eslint.config.mjs public/*.js

echo "==> android network security config"
python3 tools/check-android-cleartext.py

echo "==> no source file over 500 lines"
python3 tools/check-file-length.py

echo "==> icons match their generator"
node tools/make-icons.js
git diff --exit-code -- public/icons/

echo "==> :core tests"
(cd android && ./gradlew :core:test)

echo
echo "All checks passed."
