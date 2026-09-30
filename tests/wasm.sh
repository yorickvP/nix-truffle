#!/usr/bin/env bash
# Tests of builtins.wasm (Determinate Nix's, run with GraalWasm):
# - the cases of Determinate Nix's tests/functional/wasm.sh, with its fib.wat, fib.wasm and
#   oob.wat (the 'wat' ones need wat2wasm from wabt on the PATH);
# - with PLUGINS=... (and WASI=... for the WASI ones) pointing at the built plugins of
#   https://github.com/DeterminateSystems/nix-wasm-rust (its nix-wasm-plugins and
#   nix-wasi-plugins packages) and NIX_WASM_RUST at a checkout of it, its test suite: every
#   nix-wasm-plugin-*/tests/*.nix must evaluate to its .exp file.
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
truffle="${TRUFFLE:-$root/bin/nix-truffle}"
flags=(--extra-experimental-features wasm-builtin)
cd "$root/tests/wasm"
pass=0; fail=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then pass=$((pass+1)); else fail=$((fail+1)); echo "FAIL $1: expected '$2', got '$3'"; fi
}

check fib.wat 165580141 "$("$truffle" "${flags[@]}" -E 'builtins.wasm { wat = builtins.readFile ./fib.wat; function = "fib"; } 40' 2>&1)"
check fib.wasm 165580141 "$("$truffle" "${flags[@]}" -E 'builtins.wasm { path = ./fib.wasm; function = "fib"; } 40' 2>&1)"
out="$("$truffle" "${flags[@]}" -E 'builtins.wasm { wat = builtins.readFile ./oob.wat; function = "oob"; } 0' 2>&1)"
check oob.wat "error: Wasm memory access out of bounds" "$(grep -o 'error: Wasm memory access out of bounds' <<<"$out")"
check feature-flag false "$("$truffle" -E 'builtins ? wasm')"

if [[ -n "${PLUGINS:-}" && -n "${NIX_WASM_RUST:-}" ]]; then
  cd "$NIX_WASM_RUST"
  for i in nix-wasm-plugin-*/tests/*.nix; do
    [[ -z "${WASI:-}" && "$i" == *wasi* || -z "${WASI:-}" && "$i" == *quickjs* ]] && continue
    base="$(dirname "$i")/$(basename "$i" .nix)"
    out="$("$truffle" eval --extra-experimental-features 'nix-command wasm-builtin' --json --impure \
      -I plugins="$PLUGINS" -I wasi="${WASI:-/nonexistent}" --file "$i" 2>/dev/null)"
    check "$i" "$(cat "$base.exp")" "$out"
  done
fi
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
