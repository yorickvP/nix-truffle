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
cat > schema.pkl <<'EOF'
class Service { enable: Boolean?; port: Int(isBetween(0, 65535))?; tags: Listing<String>; env: Mapping<String, String> }
services: Mapping<String, Service>
main: Service
name: String|Dynamic = "default"
count: Int?
extra: Listing<String> = new { "from-schema" }
function force(c) = new Dynamic { priority = 50; content = c }
EOF
cat > common.pkl <<'EOF'
amends "schema.pkl"
extra { "from-common" }
main { tags { "a" } }
EOF
cat > layered.pkl <<'EOF'
amends "common.pkl"
services { ["web"] { enable = true; port = 80; tags {} } }
main { enable = null; tags { "b" }; env { ["X"] = "1" } }
count = null
local helper = 1 + 1
extra { "from-host-\(helper)" }
name = module.force("x")
EOF
cat > lazy.pkl <<'EOF'
amends "schema.pkl"
import "nix:self" as self
count = self.main.port + 1
main { port = 8080; enable = throw("not used") }
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
check "derivations come back as themselves" '[ true true "x" ]' \
  'let p = derivation { name = "x"; builder = "/bin/sh"; system = "x86_64-linux"; }; r = builtins.pkl { text = "import \"nix:d\" as d\nx = d.p\nxs { d.p; d.p.`out` }\nn = d.p.name"; nix.d.p = p; }; in [ (r.x == p) (builtins.elemAt r.xs 1 == p.out) r.n ]'
check "defaults = false: what the files set" '{ count = null; extra = [ "from-common" "from-host-2" ]; main = { enable = null; env = { X = "1"; }; tags = [ "a" "b" ]; }; name = { content = "x"; priority = 50; }; services = { web = { enable = true; port = 80; tags = [ ]; }; }; }' \
  'builtins.pkl { module = ./layered.pkl; defaults = false; }' --strict
check "defaults = false: lazily" '8081' 'let r = builtins.pkl { module = ./lazy.pkl; defaults = false; nix.self = r; }; in r.count'
check "defaults = false: Pkl's errors when used" 'error: Pkl: not used' 'let r = builtins.pkl { module = ./lazy.pkl; defaults = false; nix.self = r; }; in r.main.enable'
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
  # examples/pkl/package: a package in Pkl, and one amending it, are the same as in Nix.
  check "packages in Pkl" 'true' "let pkgs = import <nixpkgs> { }; p = import $root/examples/pkl/package { inherit pkgs; }; in p.hello-quiet.drvPath == (pkgs.stdenv.mkDerivation { pname = \"hello-quiet\"; inherit (pkgs.hello) version src; nativeBuildInputs = [ pkgs.perl ]; doCheck = false; configureFlags = [ \"--disable-nls\" ]; meta = { description = \"A program that produces a familiar, friendly greeting\"; homepage = \"https://www.gnu.org/software/hello/manual/\"; license = pkgs.lib.licenses.gpl3Plus; mainProgram = \"hello\"; }; }).drvPath" -I "nixpkgs=$NIXPKGS"
  # The schema of a configuration with a Pkl module, before there is one.
  check "NixOS schema without its Pkl module" 'true' "let pkl = import $root/nixos/pkl.nix { lib = import <nixpkgs/lib>; }; nixos = import <nixpkgs/nixos/lib/eval-config.nix> { system = \"x86_64-linux\"; modules = [ (pkl.module ./missing-schema.pkl) { system.stateVersion = \"25.11\"; } ]; }; in builtins.stringLength (pkl.schemaOf nixos) > 1000000" -I "nixpkgs=$NIXPKGS"
  printf 'amends "%s"\nservices { openssh { enabel = true } }\n' "$example/nixos.pkl" > typo.pkl
  check "NixOS option typo" 'error: Pkl: Cannot find property `enabel` in object of type `nixos#O_services_openssh`.' 'builtins.pkl { module = ./typo.pkl; }'
fi

echo "$passed passed, $failed failed"
((failed == 0))
