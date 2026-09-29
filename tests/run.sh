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

# Instantiation: write fresh (salted) derivations with nix-truffle first, then check that
# nix-instantiate computes the same .drv paths.
salt="t$RANDOM$RANDOM"
ours="$("$root/bin/nix-truffle" --instantiate "$root/tests/instantiate.nix" --argstr salt "$salt" 2>/dev/null)"
theirs="$(nix-instantiate "$root/tests/instantiate.nix" --argstr salt "$salt" 2>/dev/null)"
if [[ -n "$ours" && "$ours" == "$theirs" ]]; then
  pass=$((pass+1))
else
  fail=$((fail+1)); echo "FAIL tests/instantiate.nix"; echo "  nix:     $theirs"; echo "  truffle: $ours"
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

# Real nixpkgs packages: the .drv path hashes the entire build closure (SLOW=1 adds chromium and nixosTests.cosmic).
pkgs=(hello)
[[ -n "${SLOW:-}" ]] && pkgs+=(chromium nixosTests.cosmic)
if nix-instantiate --eval -E '<nixpkgs>' >/dev/null 2>&1; then
  for pkg in "${pkgs[@]}"; do
    theirs="$(nix-instantiate '<nixpkgs>' -A "$pkg" 2>/dev/null)"
    ours="$("$root/bin/nix-truffle" --instantiate --read-only '<nixpkgs>' -A "$pkg" 2>&1)"
    if [[ -n "$ours" && "$ours" == "$theirs" ]]; then
      pass=$((pass+1))
    else
      fail=$((fail+1)); echo "FAIL nixpkgs#$pkg"; echo "  nix:     $theirs"; echo "  truffle: $ours" | head -3
    fi
  done
fi

echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
