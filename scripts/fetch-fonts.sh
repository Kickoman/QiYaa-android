#!/usr/bin/env bash
# Downloads the IBM Plex fonts (SIL OFL 1.1) used by the UI into app/src/main/res/font.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE="https://raw.githubusercontent.com/IBM/plex/master/packages"
OUT="app/src/main/res/font"
mkdir -p "$OUT"
for fam in Mono Sans; do
  lower=$(echo "$fam" | tr '[:upper:]' '[:lower:]')
  for w in Regular Medium SemiBold; do
    wl=$(echo "$w" | tr '[:upper:]' '[:lower:]')
    curl -fsSL "$BASE/plex-$lower/fonts/complete/ttf/IBMPlex$fam-$w.ttf" -o "$OUT/ibm_plex_${lower}_${wl}.ttf"
    echo "fetched ibm_plex_${lower}_${wl}.ttf"
  done
done
curl -fsSL "https://raw.githubusercontent.com/IBM/plex/master/LICENSE.txt" -o "scripts/OFL-IBM-Plex.txt"
