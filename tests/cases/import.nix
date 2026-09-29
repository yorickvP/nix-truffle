let lib = import ./_lib.nix; in [ (lib.double 21) lib.version (import ./_lib.nix == lib) ]
