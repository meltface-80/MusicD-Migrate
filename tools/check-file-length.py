#!/usr/bin/env python3
"""No source file in this repository may exceed 500 lines.

The owner's rule, enforced here so it holds without anybody remembering it.
Counted as RAW lines — comments included — because that is what a reader has
to scroll through, and because a number nobody can dispute is a number
nobody argues about. Markdown is exempt: README.md and CLAUDE.md are prose.

When a file approaches the limit, split it by responsibility before adding to
it — see "File size" in CLAUDE.md for how the existing splits were drawn.

Run from anywhere: python3 tools/check-file-length.py
"""
import os
import subprocess
import sys

LIMIT = 500
EXTENSIONS = (".kt", ".kts", ".js", ".mjs", ".css", ".html", ".py", ".sh", ".yml", ".yaml",
              ".xml", ".json")


def tracked_files(root):
    out = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard"],
                         cwd=root, capture_output=True, text=True, check=True).stdout
    return [f for f in out.splitlines() if f.endswith(EXTENSIONS)]


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    too_long = []
    for rel in tracked_files(root):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as f:
            n = sum(1 for _ in f)
        if n > LIMIT:
            too_long.append((n, rel))
    if too_long:
        for n, rel in sorted(too_long, reverse=True):
            print(f"::error file={rel}::{rel} is {n} lines; the limit is {LIMIT}. "
                  "Split it by responsibility (see 'File size' in CLAUDE.md).")
        return 1
    print(f"every source file is {LIMIT} lines or fewer")
    return 0


if __name__ == "__main__":
    sys.exit(main())
