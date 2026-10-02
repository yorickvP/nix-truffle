# Development

```sh
nix develop            # GraalVM CE 25 + Maven (see flake.nix)
mvn -q package         # compiles, and writes target/classpath.txt
bin/nix-truffle ...    # runs what was compiled (see cli.md)
bin/build-native       # native executable target/nix-truffle (see performance.md)
tests/run.sh           # differential tests against nix-instantiate (SLOW=1 adds chromium)
bench/run.sh           # timing against nix-instantiate
nix flake check        # builds the package, runs a few commands and the language server's tests
```

How the source is laid out, and how the evaluator works, is in [internals](internals.md).

## Tests

`tests/run.sh` evaluates every `tests/cases/*.nix` with both `nix-instantiate --eval --strict`
and nix-truffle and diffs the output (errors are compared as "error"). The cases include
`derivations.nix`, which covers most derivation features. The runner also:

- checks the polyglot example against a golden file;
- instantiates `tests/instantiate.nix` with fresh, salted paths (nix-truffle writes them first,
  then `nix-instantiate` has to agree);
- when `<nixpkgs>` is available, diffs `examples/nixpkgs-lib.nix` (including `evalModules`) and
  the `.drv` paths of `hello` and, with `SLOW=1`, `chromium` and `nixosTests.cosmic`.

Currently all 37 checks pass against Lix 2.94 (with `SLOW=1`).

`tests/attrs.sh` checks `//` and attribute lookups on random attribute sets against plain
implementations. `bench/heap.sh EXPR` shows the live heap after an evaluation, by class (with
`builtins.getFlake`, a NixOS configuration's values stay alive). `bench/owners.sh DUMP` reads
a heap dump (`jcmd PID GC.heap_dump FILE`) and says which fields hold the arrays, strings and
thunks in it.

`NIX=/path/to/cppnix/bin/nix tests/eval-cli.sh` compares `nix-truffle eval` with CppNix's
`nix eval` (see [cli](cli.md#nix-truffle-eval); `NIXPKGS=1` adds cases that fetch nixpkgs); run it with
`NIX_CONFIG="eval-cores = 8"` too. `NIX=... tests/parallel.sh` evaluates expressions that
workers share (cycles, errors, `max-call-depth`) with `eval-cores = 8`, several times each, and
compares them with CppNix. `tests/repl.sh` drives `nix-truffle repl` (files, flakes, `:l`, `:lf`,
`:r`). `tests/pkl.sh` tests Pkl from Nix. `tests/lsp.py` (Python 3) drives `nix-truffle lsp`
(with `NIXPKGS=...`, NixOS options and nixpkgs too; `nix flake check` runs it without). `tests/wasm.sh` tests
`builtins.wasm`, and with `PLUGINS`, `WASI` and `NIX_WASM_RUST` set runs nix-wasm-rust's test
suite too.

## nix-pbt

[nix-pbt](https://github.com/yorickvP/nix-pbt) runs property-based differential tests (random
expressions, builtins on arbitrary arguments, fetchers on generated repositories and
tarballs, flake graphs with `follows` and overrides, flake references) against a reference
evaluator. nix-truffle speaks its server protocol with `--pbt-server`, and passes its
builtins, fetcher, flake and flake-reference suites against CppNix 2.35:

```sh
NIX_PBT_EVALUATORS="cppnix=server:nix-pbt-server-capi --option extra-experimental-features flakes;\
truffle=server:$PWD/bin/nix-truffle --pbt-server --option extra-experimental-features flakes --option polyglot false" \
NIX_PBT_FLAKE_LOCK="cppnix=nix flake lock --extra-experimental-features 'nix-command flakes';\
truffle=$PWD/bin/nix-truffle flake lock --option extra-experimental-features flakes" \
cargo test
```

## Benchmarks

`bench/run.sh` on this machine (Lix 2.94 vs GraalVM CE 25.3):

| benchmark | nix-instantiate | nix-truffle, one run incl. JVM start | nix-truffle warm |
|---|---|---|---|
| `fib.nix` (fib 32, one thunk per call) | 0.59 s | 0.73 s | 84 ms |
| `lazy.nix` (lazy prime stream, attrset fixpoint) | 0.48 s | 0.80 s | 95 ms |
