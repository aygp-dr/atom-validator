#!/usr/bin/env bash
# demo-script.sh — Scripted demo for asciinema recording
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

# Simulate typing with delay
type_slow() {
  echo -n "$ "
  for ((i=0; i<${#1}; i++)); do
    echo -n "${1:$i:1}"
    sleep 0.03
  done
  echo ""
  sleep 0.3
}

clear
echo "╔════════════════════════════════════════════════════════════╗"
echo "║  atom-validator: RFC 4287 Atom Feed Validation             ║"
echo "║  https://clojars.org/org.clojars.apace/atom-validator      ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
sleep 2

# Show installation
echo "=== Installation ==="
echo ""
type_slow "cat deps.edn | head -8"
head -8 deps.edn
sleep 2
echo ""

# Start REPL demo
echo "=== REPL Demo ==="
echo ""
type_slow "clojure -M:dev"
sleep 1

# Run clojure with demo commands
clojure -M:dev << 'REPL'
(println "\n;; Load the validator")
(require '[atom-validator.core :as v])
(println "=> atom-validator.core loaded\n")

(println ";; Validate Clojure releases feed")
(def result (v/validate-feed (slurp "https://github.com/clojure/clojure/releases.atom")))
(println "Valid:" (:valid? result))
(println "Title:" (get-in result [:feed :title]))
(println "Entries:" (count (get-in result [:feed :entries])))
(println "")

(println ";; Validate XKCD feed")
(def xkcd (v/validate-feed (slurp "https://xkcd.com/atom.xml")))
(println "Valid:" (:valid? xkcd))
(println "Title:" (get-in xkcd [:feed :title]))
(println "")

(println ";; Quick validation check")
(println "(v/valid? feed)" "=>" (v/valid? (slurp "https://xkcd.com/atom.xml")))
(println "")

(println ";; Parse and inspect a feed")
(def feed (v/parse-feed (slurp "https://github.com/emacs-mirror/emacs/releases.atom")))
(println "Latest Emacs releases:")
(doseq [e (take 3 (:entries feed))]
  (println " -" (:title e)))

(System/exit 0)
REPL

echo ""
echo "=== Done ==="
sleep 2
