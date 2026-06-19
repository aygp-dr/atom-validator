#!/usr/bin/env bash
# record-demo.sh — Record atom-validator REPL demo with asciinema
# Usage: scripts/record-demo.sh [output.cast]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CAST="${1:-$PROJECT_DIR/doc/demo.cast}"
GIF="${CAST%.cast}.gif"

cd "$PROJECT_DIR"

echo "Recording atom-validator demo..."
echo "Cast file: $CAST"

asciinema rec -t "atom-validator: RFC 4287 Atom Feed Validation" \
  --idle-time-limit 2 \
  -c "bash $SCRIPT_DIR/demo-script.sh" \
  "$CAST"

echo ""
echo "Recorded: $CAST"

# Convert to GIF if agg is available
if command -v agg >/dev/null 2>&1; then
  echo "Converting to GIF..."
  agg --theme monokai --font-size 14 "$CAST" "$GIF"
  echo "GIF: $GIF ($(du -h "$GIF" | cut -f1))"
else
  echo "Install agg to convert to GIF: pkg install asciinema-agg"
fi
