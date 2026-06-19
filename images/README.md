# Screenshots

X11 Emacs screenshots demonstrating atom-validator usage patterns.

## Screenshot Index

| # | File | Description |
|---|------|-------------|
| 01 | `01-org-literate.png` | Org-mode literate programming with inline results |
| 02 | `02-babashka-eshell.png` | Babashka script with eshell execution |
| 03 | `03-cider-repl.png` | CIDER REPL split with source code |
| 04 | `04-error-handling.png` | Validation error examples with REPL (split window) |
| 05 | `05-property-testing.png` | Property-based testing with test.check + REPL |
| 06 | `06-test-runner.png` | Test runner with REPL showing results (split window) |

## Setup

Screenshots captured with:
- Xvfb virtual framebuffer (1400x900x24)
- `scrot` for X11 capture
- ImageMagick `magick -trim +repage` to remove black background
- Emacs 30.2 with nyan-mode

## Recreate

```bash
# Start Xvfb
Xvfb :99 -screen 0 1400x900x24 &

# Capture
DISPLAY=:99 scrot /tmp/screenshot.png

# Trim black background
magick /tmp/screenshot.png -trim +repage images/final.png
```
