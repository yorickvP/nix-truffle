#!/usr/bin/env bash
# Compare `nix-instantiate --eval --strict` with nix-truffle, cold (one JVM run) and warm (in-process repeats).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TIMEFORMAT='%R s'
for b in "$root"/bench/*.nix; do
  name="$(basename "$b" .nix)"
  lix="$( { time nix-instantiate --eval --strict "$b" >/dev/null; } 2>&1 )"
  cold="$( { time "$root/bin/nix-truffle" "$b" >/dev/null; } 2>&1 )"
  warm="$("$root/bin/nix-truffle" --repeat 10 --time "$b" 2>&1 >/dev/null | tail -1 | sed 's/run 10: //')"
  printf '%-8s nix: %-9s nix-truffle cold: %-9s warm (10th run): %s\n' "$name" "$lix" "$cold" "$warm"
done
