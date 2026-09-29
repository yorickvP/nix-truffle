# Instantiation test: run with --argstr salt <random> so every path is new.
{ salt ? "none" }:
let
  sys = "x86_64-linux";
  base = derivation { name = "base"; builder = "/bin/sh"; system = sys; inherit salt; };
  src = builtins.path { path = ./cases/_src; name = "src-${salt}"; filter = p: t: builtins.match ".*\\.log" p == null; };
  file = builtins.toFile "file-${salt}" "uses ${src}";
  structured = derivation {
    name = "structured"; builder = "/bin/sh"; system = sys; __structuredAttrs = true;
    deps = [ base file ]; inherit salt;
  };
  deep = derivation {
    name = "deep"; builder = "/bin/sh"; system = sys; inherit salt;
    drv = structured.drvPath; script = ./cases/_src/build.sh;
  };
  fod = derivation {
    name = "fod-${salt}"; builder = "/bin/sh"; system = sys; outputHashMode = "recursive";
    outputHash = "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";
  };
in {
  inherit base structured deep fod;
  multi = derivation { name = "m"; builder = "/bin/sh"; system = sys; outputs = [ "lib" "out" ]; dep = fod; inherit salt; };
  nested = { recurseForDerivations = true; inner = base; notDrv = 5; };
}
