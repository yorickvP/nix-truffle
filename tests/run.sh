#!/usr/bin/env bash
# Differential test: nix-truffle vs. `nix-instantiate --eval --strict` on tests/cases/*.nix.
# Files starting with '_' are helpers. Errors are compared only as "error".
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cases=()
for f in "$root"/tests/cases/*.nix; do
  [[ "$(basename "$f")" == _* ]] || cases+=("$f")
done

expected="$(for f in "${cases[@]}"; do
  echo "### $(basename "$f")"
  nix-instantiate --eval --strict "$f" 2>/dev/null || echo error
done)"
actual="$("$root/bin/nix-truffle" --test "${cases[@]}" 2>/dev/null)"

pass=0; fail=0
while IFS= read -r name; do
  e="$(awk -v n="### $name" '$0==n{f=1;next} /^### /{f=0} f' <<<"$expected")"
  a="$(awk -v n="### $name" '$0==n{f=1;next} /^### /{f=0} f' <<<"$actual")"
  if [[ "$e" == "$a" ]]; then
    pass=$((pass+1))
  else
    fail=$((fail+1))
    echo "FAIL $name"
    echo "  nix:     $e"
    echo "  truffle: $a"
  fi
done < <(grep '^### ' <<<"$expected" | sed 's/^### //')
# Polyglot example: no reference implementation, so compare against a checked-in golden file.
if [[ "$("$root/bin/nix-truffle" "$root/examples/polyglot.nix" 2>&1)" == "$(cat "$root/examples/polyglot.expected")" ]]; then
  pass=$((pass+1))
else
  fail=$((fail+1)); echo "FAIL examples/polyglot.nix"
fi

# nixpkgs' lib (incl. evalModules), if <nixpkgs> is on NIX_PATH.
if expected_lib="$(nix-instantiate --eval --strict "$root/examples/nixpkgs-lib.nix" 2>/dev/null)"; then
  if [[ "$("$root/bin/nix-truffle" "$root/examples/nixpkgs-lib.nix" 2>&1)" == "$expected_lib" ]]; then
    pass=$((pass+1))
  else
    fail=$((fail+1)); echo "FAIL examples/nixpkgs-lib.nix"
  fi
else
  echo "skip examples/nixpkgs-lib.nix (no <nixpkgs> on NIX_PATH)"
fi

echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
