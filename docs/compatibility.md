# Compatibility

- **Supported:** the whole expression language (strings with interpolation, indented strings,
  paths including interpolated ones, `<nixpkgs>` via `NIX_PATH` incl. `flake:` entries, `rec`,
  `let`, `inherit (e)`, nested attribute paths, dynamic attributes, formals with defaults, `...`
  and `@`, `with`, `assert`, all operators, `__functor`, `or`, `?`), and all builtins nixpkgs
  needs to instantiate chromium and a NixOS test: derivations, string context,
  `path`/`filterSource`, `toFile`, `placeholder`, `fromTOML`, `fromJSON`/`toJSON`, `toXML`,
  `__curPos`, regexes, `genericClosure`, `compareVersions`, `hashString`/`hashFile`, and so on;
  the fetchers and flake builtins ([internals](internals.md#fetchers-and-flakes)); `builtins.pkl` and `.pkl` imports ([Pkl](pkl.md)); and the
  rest of CppNix 2.35's builtins
  (`builtins.attrNames builtins` is the same with `--option polyglot false`).
- **Regexes** are a port of libstdc++'s `std::regex` (POSIX extended grammar, the NFA and its
  depth-first matcher), since that is what CppNix uses: the same leftmost-first (not
  leftmost-longest) matches, the same corner cases, the same errors.
- **Not supported:** content-addressed or impure derivations, import-from-derivation (an
  error, since evaluation doesn't build), fetching Git LFS files, `verified-fetches`.
- **Attribute positions** (`builtins.unsafeGetAttrPos`) follow CppNix, including which builtins
  keep them (`//`, `removeAttrs`, `intersectAttrs`, `listToAttrs`) and which don't (`mapAttrs`).
  They matter for more than error messages: `nixos/lib/eval-config.nix` wraps modules
  depending on them, which changes the order in which list options such as
  `environment.systemPackages` are merged.
- **Approximations:** error messages have a location and a derivation trace but no full
  Nix-style trace. Evaluation is single-threaded unless `eval-cores` says otherwise (see
  [parallel evaluation](performance.md#parallel-evaluation)), and there is no evaluation cache.
- **Known divergence:** CppNix keeps attribute sets in the order their names were first
  interned (well-known names like `drvPath` first, then in parse order), nix-truffle sorts
  them by name. Only which error comes first differs, when forcing all attributes
  (`deepSeq`, `==`) hits several of them.
