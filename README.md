# nix-truffle

An interpreter for the Nix expression language on Truffle/GraalVM, with a JIT, and a language
server that evaluates what you're editing. It's a proof of concept, but a thorough one: it
instantiates nixpkgs and NixOS systems to the same `.drv` files as Nix.

```
$ nix-truffle --instantiate '<nixpkgs>' -A chromium
/nix/store/30509ibhwkm7i31bam9i5px7n0bvvv27-chromium-154.0.8037.57.drv
$ nix-instantiate '<nixpkgs>' -A chromium
/nix/store/30509ibhwkm7i31bam9i5px7n0bvvv27-chromium-154.0.8037.57.drv
```

A `.drv` path hashes its entire build closure. Matching Lix on chromium means all 4,135
derivations and 570 sources in it come out byte-identical: ATerm serialization,
`hashDerivationModulo`, string context, NAR hashing of local sources, structured attrs JSON,
`fromTOML`, and so on. It takes 2.7 s against Lix's 2.3 s, JVM startup and JIT warm-up
included. A full NixOS test (`nixosTests.cosmic`: 6,341 derivations) matches too, in 5.2 s
against 5.1 s.

## What's in it

- **The whole Nix language and CppNix 2.35's builtins**, with lazy semantics, string context,
  derivations, fetchers (`fetchTree`, `fetchGit`, `fetchTarball`, ...) and flakes
  (`getFlake`, lock files). See [compatibility](docs/compatibility.md) for what isn't there.
- **A `nix eval`-compatible command line**, a REPL like `nix repl`, and `flake lock`, tested
  against CppNix's output on 195 command lines ([command line](docs/cli.md)).
- **A language server** that completes and checks NixOS and home-manager options, packages and
  `lib`, finding the configuration that imports a file by itself
  ([language server](docs/language-server.md)).
- **Speed**: a warm background daemon, native executables, and parallel evaluation
  (`eval-cores`), which evaluates seven NixOS machines in 8.7 s where Lix takes 35.6 s
  ([performance](docs/performance.md)).
- **Other languages**: [Pkl](docs/pkl.md) for typed configuration (`import ./config.pkl`, and Nix
  data checked by Pkl types), [JavaScript and other Truffle languages](docs/polyglot.md), and
  Determinate Nix's [`builtins.wasm`](docs/wasm.md) on GraalWasm.

## Installing

```sh
nix run github:yorickvp/nix-truffle -- eval nixpkgs#hello.name
nix profile install github:yorickvp/nix-truffle
```

The flake has three packages:

- `.#default`: on the JVM (GraalVM CE). It starts in 0.3 s and is fastest on big evaluations,
  so it's the one for the language server.
- `.#native`: a native executable. It starts in milliseconds but is slow on big evaluations
  (the serial GC).
- `.#native-oracle`: a native executable with Oracle GraalVM's G1, in between. Oracle GraalVM is
  under the GraalVM Free Terms and Conditions, unfree in nixpkgs; the flake allows it for this
  package.

## Using it

```sh
nix-truffle eval .#nixosConfigurations.host.config.system.build.toplevel.drvPath  # like nix eval
nix-truffle eval --raw nixpkgs#hello.name
nix-truffle repl '<nixpkgs>'                      # like nix repl
nix-truffle -E 'let fib = n: if n < 2 then n else fib (n - 1) + fib (n - 2); in fib 32'
nix-truffle --instantiate '<nixpkgs>' -A hello    # like nix-instantiate
NIX_TRUFFLE_DAEMON=1 nix-truffle eval ...         # in a warm background daemon
```

It reads `nix.conf` and takes `--option` like Nix. See [command line](docs/cli.md).

## The language server

`nix-truffle lsp` speaks LSP on stdio. In Emacs:

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs '(nix-mode . ("nix-truffle" "lsp"))))
```

Open a module of a flake's NixOS configuration, and it gets that configuration's `config`,
`options` and `pkgs`. You get:

- completion of options (through submodules, `mkIf` and `mkMerge`), packages, `lib` and local
  attribute sets (`cfg.` with `cfg = config.services.nginx`);
- hover with option types, defaults and descriptions, and package metadata;
- diagnostics for options that don't exist, values of the wrong type, and conflicts with the
  other modules' definitions;
- inlay hints with package versions and option defaults;
- go to definition and formatting.

[The language server](docs/language-server.md) has setup for other editors, settings, and how it
picks a configuration.

## Documentation

- [Command line](docs/cli.md): `eval`, `repl`, settings, the legacy `nix-instantiate`-style form
- [Language server](docs/language-server.md)
- [Performance](docs/performance.md): the daemon, native images, parallel evaluation, numbers
- [Pkl](docs/pkl.md), [polyglot](docs/polyglot.md), [`builtins.wasm`](docs/wasm.md)
- [Compatibility](docs/compatibility.md): what's supported and where it differs from CppNix
- [Internals](docs/internals.md): how laziness works on Truffle, strings as bytes, derivations,
  fetchers and flakes, the source layout
- [Development](docs/development.md): building, tests (differential against CppNix and Lix,
  nix-pbt), benchmarks
