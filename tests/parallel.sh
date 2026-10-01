#!/usr/bin/env bash
# Parallel evaluation (eval-cores) against CppNix's sequential `nix eval`: every line of
# tests/parallel/cases.txt is an expression whose evaluation workers share (shared thunks,
# infinite recursions across threads, errors and max-call-depth inside the builtins that offer
# values to workers). Each runs RUNS times (default 8) with eval-cores = 8, and must give CppNix's
# value or first error line every time.
#
#   NIX=/path/to/cppnix/bin/nix tests/parallel.sh
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${NIX:?set NIX to a CppNix 2.35 nix binary}"
truffle="${TRUFFLE:-$root/bin/nix-truffle}"
runs="${RUNS:-8}"

# The value, or the first error line (traces, warnings and blank lines left out).
result() {
  grep -v '^trace\|warning\|^\s*$' | grep -o '^{.*\|^\[.*\|^".*\|^[0-9].*\|error: [a-z].*\|Terminated' | head -1
}

passed=0 failed=0
while IFS= read -r e; do
  want="$("$NIX" --extra-experimental-features nix-command eval --json --expr "$e" 2>&1 | result)"
  ok=1
  for i in $(seq "$runs"); do
    got="$(timeout 120 "$truffle" eval --option eval-cores 8 --json --expr "$e" 2>&1 | result)"
    if [[ "$got" != "$want" ]]; then
      printf 'FAIL (run %s): %s\n  cppnix:  %s\n  truffle: %s\n' "$i" "$e" "$want" "$got"
      ok=0
      break
    fi
  done
  if ((ok)); then passed=$((passed + 1)); else failed=$((failed + 1)); fi
done < "$root/tests/parallel/cases.txt"
echo "$passed passed, $failed failed"
((failed == 0))
