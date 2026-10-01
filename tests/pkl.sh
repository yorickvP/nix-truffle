#!/usr/bin/env bash
# Pkl from Nix: import of .pkl files, builtins.pkl (amending with Nix values, output, nix: URIs).
# TRUFFLE overrides bin/nix-truffle.
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
truffle="${TRUFFLE:-$root/bin/nix-truffle}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"
cp "$root"/examples/pkl/{services,prometheus}.pkl .
cat > values.pkl <<'EOF'
dur = 5.min
size = 3.mb
pair = Pair(1, "a")
dyn { "x"; "y" }
mixed { a = 1; "x" }
map = Map("k", 1)
set = Set(1, 2)
nullable: String? = null
EOF
cat > typed.pkl <<'EOF'
xs: List<Int>
names: Set<String>
limits: Map<String, Int>
EOF
cat > reads.pkl <<'EOF'
import "nix:d" as d
sum = d.a + d.b.length
s = d.s
nested = d.n.deep
asText = read("nix:d.s")
json = read("nix:d.b")
path = read("nix:file")
EOF

passed=0 failed=0
# check NAME EXPECTED EXPR [OPTION...]: the first line of the output.
check() {
  local name="$1" want="$2" expr="$3"
  shift 3
  local got
  got="$(NIX_TRUFFLE_DAEMON=0 timeout 120 "$truffle" "$@" -E "$expr" 2>&1 | head -1)"
  if [[ "$got" == "$want" ]]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL %s\n  want: %s\n  got:  %s\n' "$name" "$want" "$got"
  fi
}

check "import" '{ domain = "example.org"; services = [ ]; urls = [ ]; }' 'import ./services.pkl'
check "values" '{ dur = { unit = "min"; value = 5; }; dyn = [ "x" "y" ]; map = { k = 1; }; mixed = { "0" = "x"; a = 1; }; nullable = null; pair = [ 1 "a" ]; set = [ 1 2 ]; size = { unit = "mb"; value = 3; }; }' 'import ./values.pkl'
check "amend: defaults, entries, derived values" '[ [ "https://web.nixos.org:443" "https://api.nixos.org:8080" ] { MODE = "prod"; } "on-failure" ]' \
  'let r = builtins.pkl { module = ./services.pkl; amend = { domain = "nixos.org"; services = [ { name = "web"; port = 443; environment.MODE = "prod"; } { name = "api"; } ]; }; }; s = builtins.elemAt r.services 0; in [ r.urls s.environment s.restart ]'
check "amend: List, Set and Map types" '{ limits = { a = 1; }; names = [ "x" ]; xs = [ 1 2 ]; }' 'builtins.pkl { module = ./typed.pkl; amend = { xs = [ 1 2 ]; names = [ "x" "x" ]; limits.a = 1; }; }'
check "a constraint" 'error: Pkl: Type constraint `matches(Regex("[a-z][a-z0-9-]*"))` violated.' 'builtins.pkl { module = ./services.pkl; amend.services = [ { name = "Web"; } ]; }'
check "a type" 'error: Pkl: Expected value of type `Int`, but got type `String`.' 'builtins.pkl { module = ./services.pkl; amend.services = [ { name = "web"; port = "80"; } ]; }'
check "a unique name" 'error: Pkl: Type constraint `isDistinctBy((s) -> s.name)` violated.' 'builtins.pkl { module = ./services.pkl; amend.services = [ { name = "a"; } { name = "a"; } ]; }'
check "expression" '[ "https://a.example.org:8080" ]' 'builtins.pkl { module = ./services.pkl; amend.services = [ { name = "a"; } ]; expression = "urls"; }'
check "text" '{ x = 3; }' 'builtins.pkl { text = "x = 1 + 2"; }'
check "output" '"global:\n  scrape_interval: 1min\nscrape_configs:\n- job_name: node\n  scrape_interval: 15s\n  static_configs:\n  - targets:\n    - db:9100\n"' \
  'builtins.pkl { module = ./prometheus.pkl; output = true; amend.scrape_configs = [ { job_name = "node"; static_configs = [ { targets = [ "db:9100" ]; } ]; } ]; }'
check "nix: modules and reads" "{ asText = \"x\"; json = \"[1,2]\"; nested = true; path = \"$(NIX_TRUFFLE_DAEMON=0 "$truffle" -E 'builtins.toFile "f" "y"' | tr -d '"')\"; s = \"x\"; sum = 3; }" \
  'builtins.pkl { module = ./reads.pkl; nix = { d = { a = 1; b = [ 1 2 ]; s = "x"; n.deep = true; f = x: x; }; file = builtins.toFile "f" "y"; }; }'
check "store paths keep their context" "[ \"$(NIX_TRUFFLE_DAEMON=0 "$truffle" -E 'builtins.toFile "f" "y"' | tr -d '"')\" ]" 'builtins.attrNames (builtins.getContext (builtins.pkl { module = ./reads.pkl; nix = { d = { a = 1; b = [ ]; s = ""; n.deep = 1; }; file = builtins.toFile "f" "y"; }; }).path)' --strict
check "a missing nix: attribute" "error: Pkl: I/O error loading module \`nix:d.missing\`." 'builtins.pkl { text = "import \"nix:d.missing\" as m\nx = m.value"; nix.d = { }; }'
check "pure evaluation" "error: Pkl: access to absolute path '/etc/hostname' is forbidden in pure evaluation mode (use '--impure' to override)" \
  'builtins.pkl { text = "x = read(\"file:///etc/hostname\")"; }' --option pure-eval true
check "polyglot off" 'false' 'builtins ? pkl' --option polyglot false

# With NIXPKGS (a nixpkgs path): a NixOS system in Pkl (examples/pkl/nixos) is the same as in Nix,
# and Pkl checks it against the options' types.
if [[ -n "${NIXPKGS:-}" ]]; then
  example="$root/examples/pkl/nixos"
  NIX_TRUFFLE_DAEMON=0 "$truffle" eval --raw -I "nixpkgs=$NIXPKGS" --file "$example/schema.nix" > "$example/nixos.pkl"
  systems="$(NIX_TRUFFLE_DAEMON=0 timeout 300 "$truffle" -I "nixpkgs=$NIXPKGS" --strict "$example" 2>&1)"
  if [[ "$systems" =~ fromNix\ =\ (\"[^\"]*\").*fromPkl\ =\ (\"[^\"]*\") && "${BASH_REMATCH[1]}" == "${BASH_REMATCH[2]}" ]]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    printf 'FAIL NixOS in Pkl\n  got: %s\n' "$systems"
  fi
  printf 'amends "%s"\nservices { openssh { enabel = true } }\n' "$example/nixos.pkl" > typo.pkl
  check "NixOS option typo" 'error: Pkl: Cannot find property `enabel` in object of type `nixos#O_services_openssh`.' 'builtins.pkl { module = ./typo.pkl; }'
fi

echo "$passed passed, $failed failed"
((failed == 0))
