#!/usr/bin/env bash
# nix-truffle repl, driven through stdin: files, flakes and attribute paths given on the command
# line, :l, :lf, :r. CppNix's nix repl only takes installables, so the expected output is here.
# TRUFFLE overrides bin/nix-truffle.
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
truffle="${TRUFFLE:-$root/bin/nix-truffle}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
echo '{ a = 1; b = "two"; }' > f.nix
echo '{ x ? 0 }: { y = x + 1; }' > fn.nix
echo '{ x ? "z" }: { y = x + "b"; }' > fs.nix
echo '{ v = { a = 1; }; w = { b = "two"; }; }' > g.nix
mkdir dir && echo '{ inDir = true; }' > dir/default.nix
mkdir fl && cat > fl/flake.nix <<'EOF'
{
  outputs = { self }: {
    lib = { f = x: x + 1; n = 3; };
    legacyPackages = builtins.listToAttrs (map (system: { name = system; value.legacy = { x = 1; y = [ 1 2 ]; }; })
      [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ]);
  };
}
EOF
flakes=(--extra-experimental-features 'nix-command flakes')

passed=0 failed=0
# check NAME EXPECTED INPUT ARG...: the output lines without prompts and blank lines, joined by '|'.
check() {
  local name="$1" want="$2" input="$3"
  shift 3
  local got
  got="$(printf "$input" | NIX_TRUFFLE_DAEMON=0 timeout 120 "$truffle" repl "$@" 2>&1 \
    | sed 's/^nix-truffle> //' | grep -v '^ *$\|^Welcome' | paste -sd'|')"
  if [[ "$got" == "$want" ]]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL %s\n  want: %s\n  got:  %s\n' "$name" "$want" "$got"
  fi
}

check "a file argument" 'Added 2 variables.|a, b|1|"two"' 'a\nb\n' f.nix
check "a directory with default.nix" 'Added 1 variables.|inDir|true' 'inDir\n' dir
check "<path> from -I" 'Added 2 variables.|a, b|1' 'a\n' -I "here=$tmp" '<here/f.nix>'
check "a function, called with --arg" 'Added 1 variables.|y|42' 'y\n' fn.nix --arg x 41
check "--file, called with --argstr" 'Added 1 variables.|y|"ab"' 'y\n' --file fs.nix --argstr x a
check "--expr, not called (as in CppNix)" "error: expected a set but found a function: «lambda @ «string»:1:1»" '' --expr '{ x }: { y = x; }' --argstr x a
check "--expr and an attribute path" 'Added 2 variables.|b, c|7' 'b + c\n' --expr '{ a = { b = 3; c = 4; }; }' a
check "--file and attribute paths" 'Added 1 variables.|a|Added 1 variables.|b|1|"two"' 'a\nb\n' --file g.nix v w
check "many variables, and :ll" "Added 25 variables.|v0, v1, v10, v11, v12, v13, v14, v15, v16, v17, v18, v19, v2, v20, v21, v22, v23, v24, v3, v4|... and 5 more; view with :ll|$(printf 'v%s|' 0 1 1{0..9} 2 2{0..4} {3..9})6" ':ll\nv6\n' --expr 'builtins.listToAttrs (builtins.genList (i: { name = "v${toString i}"; value = i; }) 25)'
check "a flake (its outputs)" 'Added 2 variables.|legacyPackages, lib|4' 'lib.f 3\n' "${flakes[@]}" ./fl
check "a flake attribute" 'Added 2 variables.|x, y|[|  1|  2|]' 'y\n' "${flakes[@]}" './fl#legacy'
check ":l and :lf" 'Added 2 variables.|a, b|Added 2 variables.|legacyPackages, lib|"two"|3' ':l f.nix\n:lf ./fl\nb\nlib.n\n' "${flakes[@]}"
check ":r drops bindings and reloads" "Added 2 variables.|a, b|Added 2 variables.|a, b|error: undefined variable 'z'|       at «repl»:1:1|1" ':l f.nix\nz = 5\n:r\nz\na\n'
check "a missing file" "error: path '$tmp/missing.nix' does not exist|1" '1\n' missing.nix
check "an unknown flag" "error: unrecognised flag '--nope'|Try 'nix-truffle repl --help' for more information." '' --nope

echo "$passed passed, $failed failed"
((failed == 0))
