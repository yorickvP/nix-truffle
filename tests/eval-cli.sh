#!/usr/bin/env bash
# Differential test of `nix-truffle eval` against CppNix's `nix eval`: for every line of
# tests/eval-cli/cases.txt (a shell-quoted command line), both run in a fresh copy of
# tests/eval-cli/fixtures (in fx/ for lines starting with '@fx'), and their stdout, exit status
# and the lock file they may write must be the same.
#
#   NIX=/path/to/cppnix/bin/nix tests/eval-cli.sh
#
# NIX must be CppNix 2.35 (Lix's `nix eval` prints some values differently). NIXPKGS=1 adds the
# cases in nixpkgs-cases.txt, which fetch nixpkgs. VERBOSE=1 lists passing cases too.
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${NIX:?set NIX to a CppNix 2.35 nix binary}"
truffle="${TRUFFLE:-$root/bin/nix-truffle}"
features=(--extra-experimental-features nix-command --extra-experimental-features flakes)
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
work="$tmp/work"

cases=("$root/tests/eval-cli/cases.txt")
[[ -n "${NIXPKGS:-}" ]] && cases+=("$root/tests/eval-cli/nixpkgs-cases.txt")

pass=0; fail=0
while IFS= read -r line; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  dir="$work"; args="$line"
  if [[ "$line" == @fx* ]]; then dir="$work/fx"; args="${line#@fx }"; fi
  for who in nix truffle; do
    rm -rf "$work"; cp -r "$root/tests/eval-cli/fixtures" "$work"
    cmd=$([ $who = nix ] && echo "$NIX" || echo "$truffle")
    (cd "$dir" && eval "\"$cmd\" ${features[*]} eval $args") > "$tmp/out-$who" 2> "$tmp/err-$who"
    echo $? > "$tmp/rc-$who"
    cat "$work/fx/flake.lock" > "$tmp/lock-$who" 2>/dev/null || : > "$tmp/lock-$who"
  done
  if cmp -s "$tmp/out-nix" "$tmp/out-truffle" && cmp -s "$tmp/rc-nix" "$tmp/rc-truffle" && cmp -s "$tmp/lock-nix" "$tmp/lock-truffle"; then
    pass=$((pass+1))
    [[ -n "${VERBOSE:-}" ]] && echo "ok   $line"
  else
    fail=$((fail+1))
    echo "FAIL $line"
    echo "  nix     [$(cat "$tmp/rc-nix")]: $(head -c 300 "$tmp/out-nix")"
    echo "  truffle [$(cat "$tmp/rc-truffle")]: $(head -c 300 "$tmp/out-truffle")"
    cmp -s "$tmp/lock-nix" "$tmp/lock-truffle" || echo "  the lock files differ"
    echo "  nix stderr:     $(grep -v '^\s*$' "$tmp/err-nix" | tail -2 | tr '\n' ' ')"
    echo "  truffle stderr: $(grep -v '^\s*$' "$tmp/err-truffle" | tail -2 | tr '\n' ' ')"
  fi
done < <(cat "${cases[@]}")
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
