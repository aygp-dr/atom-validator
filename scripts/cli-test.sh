#!/usr/bin/env bash
# Smoke-test bin/atom-validate against test/fixtures/.
#
# Exit-code contract:
#   0 = valid feed
#   1 = invalid feed (validation errors)
#   2 = error (parse failure, network, invalid args)

set -u

# Locate repo root (scripts/ is a direct child).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

if ! command -v bb >/dev/null 2>&1; then
  echo "bb (Babashka) not installed" >&2
  exit 1
fi

if [ ! -x bin/atom-validate ]; then
  echo "bin/atom-validate not executable" >&2
  exit 1
fi

echo "==> cli-test: bin/atom-validate against test/fixtures/"

fail=0

check() {
  local desc="$1"
  local expected="$2"
  shift 2
  bin/atom-validate "$@" >/dev/null 2>&1
  local got=$?
  if [ "$got" = "$expected" ]; then
    printf "  ok   %s (exit %s)\n" "$desc" "$got"
  else
    printf "  FAIL %s (expected %s, got %s)\n" "$desc" "$expected" "$got"
    fail=1
  fi
}

check "help"            0 --help
check "version"         0 --version
check "valid atom"      0 test/fixtures/valid-feed.xml
check "valid atom json" 0 --format json test/fixtures/valid-feed.xml
check "invalid atom"    1 test/fixtures/invalid-feed.xml
check "no-semantic"     1 --no-semantic test/fixtures/invalid-feed.xml
check "strict invalid"  1 --strict test/fixtures/invalid-feed.xml
check "quiet invalid"   1 --quiet test/fixtures/invalid-feed.xml
check "valid rss"       0 test/fixtures/valid-rss.xml
check "valid jsonfeed"  0 test/fixtures/feed.json
check "jsonfeed json"   0 --format json test/fixtures/feed.json
check "xkcd warn only"  0 test/fixtures/xkcd.xml
check "strict on warn"  1 --strict test/fixtures/xkcd.xml
check "github clojure"  0 test/fixtures/github-clojure.xml
check "missing file"    2 /nonexistent/feed.xml
check "no args"         2

# stdin
if cat test/fixtures/valid-feed.xml | bin/atom-validate - >/dev/null 2>&1; then
  printf "  ok   stdin (exit 0)\n"
else
  printf "  FAIL stdin (expected 0, got %s)\n" "$?"
  fail=1
fi

# garbage stdin -> parse error (exit 2)
echo "garbage" | bin/atom-validate - >/dev/null 2>&1
got=$?
if [ "$got" = "2" ]; then
  printf "  ok   garbage stdin (exit 2)\n"
else
  printf "  FAIL garbage stdin (expected 2, got %s)\n" "$got"
  fail=1
fi

if [ "$fail" = "0" ]; then
  echo "cli-test: all checks passed"
  exit 0
else
  echo "cli-test: failures detected" >&2
  exit 1
fi
