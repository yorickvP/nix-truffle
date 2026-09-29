let
  sys = "x86_64-linux";
  base = derivation { name = "base"; builder = "/bin/sh"; system = sys; args = [ "-c" "echo hi" ]; };
  multi = derivation {
    name = "multi"; builder = "/bin/sh"; system = sys;
    outputs = [ "out" "dev" "lib" ];
    deps = [ base "${base}/bin" ];
    flag = true; off = false; nothing = null; n = 42; f = 1.5;
    nested = [ [ ] "a" [ "b" [ ] "c" ] null ];
    script = ./_src/build.sh;
  };
  fod = derivation {
    name = "fod"; builder = "/bin/sh"; system = sys;
    outputHash = "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="; outputHashMode = "recursive";
  };
  fodFlat = derivation {
    name = "fod-flat.tar.gz"; builder = "/bin/sh"; system = sys;
    outputHashAlgo = "sha1"; outputHash = "0000000000000000000000000000000000000000";
  };
  fodBase32 = derivation {
    name = "fod32"; builder = "/bin/sh"; system = sys;
    outputHashAlgo = "sha256"; outputHash = "1b8m03r63zqhnjf7l5wnldhh7c134ap5vpj0850ymkq1iyzicy5s";
  };
  structured = derivation {
    name = "structured"; builder = "/bin/sh"; system = sys; __structuredAttrs = true;
    outputs = [ "out" "doc" ];
    deps = [ multi.dev fod ];
    attrs = { a = 1; b = [ 1.0 0.5 "x" null true ]; path = ./_src/sub/data.txt; };
    placeholder = builtins.placeholder "doc";
  };
  ignoring = derivation { name = "ignoring"; builder = "/bin/sh"; system = sys; __ignoreNulls = true; x = null; y = "y"; };
  src = builtins.path { path = ./_src; name = "filtered-src"; filter = p: t: builtins.match ".*\\.log" p == null; };
  flat = builtins.path { path = ./_src/sub/data.txt; recursive = false; };
  deep = derivation { name = "deep"; builder = "/bin/sh"; system = sys; inherit src flat; };
  file = builtins.toFile "script.sh" "exec /bin/sh ${./_src/sub/data.txt}";
  usesFile = derivation { name = "uses-file"; builder = file; system = sys; };
in [
  base.drvPath base.outPath multi.drvPath multi.dev.outPath multi.lib.outPath multi.outputName
  fod.outPath fodFlat.outPath fodBase32.outPath structured.drvPath structured.doc.outPath
  ignoring.drvPath src flat deep.drvPath file usesFile.drvPath
  (builtins.getContext "${multi.dev}${base.drvPath}${file}")
  (builtins.hasContext "${base}") (builtins.unsafeDiscardStringContext "${base}")
  (builtins.getContext (builtins.unsafeDiscardOutputDependency base.drvPath))
  (builtins.attrNames (builtins.getContext (builtins.appendContext "x" { ${builtins.unsafeDiscardStringContext base.drvPath} = { outputs = [ "out" ]; }; })))
  (builtins.toJSON { inherit base; p = ./_src/sub/data.txt; f = [ 1.0 0.1 1.0e20 ]; })
  (builtins.substring 0 11 "${base}")
  (builtins.typeOf base) (base ? type) (map (x: x.outputName) multi.all)
  (builtins.hashString "sha256" "abc") (builtins.hashFile "sha1" ./_src/sub/data.txt)
]
