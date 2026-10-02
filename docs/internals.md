# Internals

How the evaluator works: laziness, strings, the store, fetchers and flakes, and where the
code for each is ([layout](#layout)).

## How laziness works

The design follows [thc](https://github.com/ekmett/thc), Edward Kmett's Haskell-on-Truffle
(`Thunk.java`, `Force.java`, `DispatchThunkTarget.java`, `docs/thunk-updates.md` there).

- **A thunk is a call target plus a captured environment** (`runtime/Thunk.java`). Every
  expression that needs suspending gets its own `RootNode`. The thunk holds that root's
  `RootCallTarget`, the enclosing environment, and a state: *pending*, *blackhole* or *done*.
  Re-entering a blackholed thunk raises `infinite recursion encountered`. On completion the
  thunk keeps only the answer and drops the target and environment so they can be collected. If
  evaluation throws, it goes back to *pending*, as in CppNix, so `tryEval` and later forces see
  the error again.
- **Forcing is a specializing node with an inline cache on the thunk's target**
  (`nodes/ForceNode.java`). A site that only ever saw values compiles to a type check. A site
  that saw a thunk that was already done compiles to a state check. Pending thunks are
  dispatched on their call target through a `DirectCallNode` (limit 3, then indirect), so Graal
  can inline the thunk body into the code that forces it. Tier-2 compilation traces for
  `bench/fib.nix` show exactly that: `thunk@fib.nix:2:42` (the `n - 1` argument) is inlined
  into `fib`, and `fib` into itself a few levels deep.
- **Forced bindings are written back** (thc's "writeForced", `nodes/ReadVarNode.java`). After a
  strict variable read forces a thunk, the answer replaces the thunk in the variable's slot, so the
  next read skips the thunk entirely. Bindings are immutable, so the program can't tell. The
  same is done for attrset values and list elements.
- **Thunks are only created when needed** (`Translator.lazy`, like CppNix's `maybeThunk` and
  thc's "already evaluated" proofs). Literals, lambdas and references to lexical variables are
  never wrapped: a variable in a lazy position shares the binding's existing thunk. The
  exception is a reference to another member of the same `let`/`rec`/formals group, whose slot
  may not be written yet (`let a = b; b = 1;`).
- **Environments and scoping are resolved statically.** Every lambda body and every thunk body
  is its own root, called with the environment it closes over. A root's environment is an
  `Object[]`: the enclosing environment, then its local variables. `let`, `rec`, `with` and
  formals just allocate slots in it, and a root without any uses the enclosing one. Variables
  are `(depth, slot)` in that chain. Nix has no loops, so a node runs at most once per
  activation, and one slot per binding is enough. Unbound names fall back to the enclosing
  `with` scopes at runtime (after builtins, as in Nix). These are plain arrays rather than
  Truffle frame slots: every environment a closure or thunk keeps would otherwise be a frame
  object with arrays for the arguments, the slots, primitive slots and their tags, and those
  environments are much of an evaluation's memory.
- **Builtins that build lazy structures** (`map`, `genList`, `mapAttrs`, ...) create thunks
  whose target is a shared "apply f to x" root, so every thunk goes through the same `ForceNode`
  machinery.
- **Attrsets use a shared key array as a cheap shape.** An attrset literal sorts its keys once
  at translation time and shares that array with every set it creates, so selection
  (`nodes/SelectStepNode.java`) caches the attribute index per key-array identity. Formals
  binding does the same. Sets made at runtime (`//`, `listToAttrs`, `removeAttrs`, ...) share
  key arrays too, through a cache by contents (and for `//`, by the two sides' arrays): only a
  quarter of a NixOS evaluation's key arrays are distinct.
- **Attribute positions are spliced, not copied.** `//` keeps positions like CppNix, but most
  updates add a few attributes to a big set, and the evaluation keeps hundreds of thousands of
  their results. A result's positions are runs taken from those of its two sides, and
  `unsafeGetAttrPos` follows them (flattened when nested deeper than eight).

## Strings are bytes

Nix strings are byte strings, not text: `stringLength "é"` is 2, `substring 0 1 "é"` is the
lone byte 0xC3, and file names and file contents are whatever bytes they are. nix-truffle
stores them as Java strings with one `char` per byte (Latin-1), so every string operation is
byte-exact. The conversion to and from real text happens only at the edges: source code and
command-line arguments are UTF-8-encoded on the way in, output is written as raw bytes, and
strings that cross into other languages or the host are decoded as UTF-8 (a string that
isn't valid UTF-8 crosses as a `ByteString` interop object instead, and comes back unchanged).
File system access goes through libc (`fs/Fs.java`, via the FFM API) so that file names that
aren't valid UTF-8 work too.

## Derivations and the store

- **String context.** Strings carry the store paths they refer to. Context-free strings stay
  plain Java `String`s; strings with context are `NixString`s. Coercion follows CppNix:
  interpolation copies paths to the store, derivation attributes use "coerce more", and the
  string builtins propagate context.
- **`derivationStrict`** (`builtins/StoreBuiltins.java`) is a port of CppNix's. It builds env
  vars or `__structuredAttrs` JSON (with nlohmann's float formatting), collects inputs from
  context (a `drvPath`'s context pulls in its whole closure), and computes fixed-output paths
  (any hash encoding) or input-addressed paths via `hashDerivationModulo` over the ATerm
  (`store/Derivation.java`). `derivation` and `<nix/fetchurl.nix>` are CppNix 2.35's own
  files, extracted verbatim.
- **Store paths** are computed like libstore's (`store/StorePaths.java`, `store/Hash.java`
  with Nix base32, `store/Nar.java` for sources, including `builtins.path` filters). Paths go
  into a per-evaluation registry; nothing touches the store while evaluating.
- **Instantiation** (`--instantiate`) writes the closure of every `.drv` (sources, `toFile`
  texts, input derivations) to the real store. It uses a small worker-protocol client
  (`store/DaemonClient.java`, protocol 1.35, content-addressed `AddToStore`, so untrusted users
  can do it). The daemon computes each path itself, and a mismatch with ours is an error.

## Fetchers and flakes

`fetch/` is a port of libfetchers: inputs and input schemes (`path`, `git`, `github`,
`gitlab`, `sourcehut`, `tarball`, `file`, `hg`, `indirect`), URL parsing and printing, flake
registries, `fetchTree` with its lock checks (`narHash`, `rev`, `__final`), and the
attributes it returns (`narHash`, `rev`, `lastModified`, a lazy `revCount`, ...). Git
repositories are read with the `git` CLI, Mercurial ones with `hg`. Tarballs (gzip, bzip2,
xz, zstd, zip) are unpacked in-process.

Fetched things are cached in **`~/.cache/nix-truffle/`** (`$XDG_CACHE_HOME`), separate from
CppNix's and Lix's caches: `git-v1/` holds bare Git repositories that remotes are fetched
into, `hg-v1/` Mercurial clones, and `fetcher-cache-v1/` one small JSON file per entry
(downloads with their ETags, honouring `tarball-ttl`, and the store paths of fetched trees).

Unlike CppNix, which reads fetched trees lazily and only copies them to the store when a
store path is needed, nix-truffle copies every fetched tree to the store right away (like
`lazy-trees = false`). The resulting paths and attributes are the same.

Flakes (`builtins/FlakeBuiltins.java`, `fetch/FlakeRef.java`, `fetch/LockFile.java`) follow
libflake: flake references, reading `flake.nix` (inputs, `follows`, nested overrides,
`flake = false`, relative `path:./x` inputs, `?dir=`, `inputs.self`), `computeLocks`, lock
files (version 7; `nix-truffle flake lock` writes the same file as `nix flake lock`), and
`call-flake.nix` to call the outputs. `builtins.getFlake` locks in memory and doesn't write
the lock file, like Nix. `nixConfig` is checked but not applied.

## Layout

```
src/main/java/nixtruffle/
  parser/      hand-written lexer + recursive-descent parser → Expr records
  Translator   scope resolution, slots, where thunks go → Truffle nodes
  nodes/       ForceNode, ReadVarNode, ApplyNode/DispatchNode, SelectStepNode, operators, ...
  runtime/     Thunk, NixAttrs, NixList, NixString (context), NixLambda, interop (Foreign), printers
  builtins/    primops, derivations and context (StoreBuiltins), files and import (FileBuiltins),
               fetchers (FetchBuiltins), flakes (FlakeBuiltins), regexes, JSON, TOML, versions
  fetch/       libfetchers and libflake: inputs, schemes, URLs, registries, caches, lock files
  fs/          byte-exact file system access through libc
  store/       hashes, store paths, NAR, Derivation (ATerm), store registry, daemon client
  util/        JSON (nlohmann-compatible output), Proc (a command's environment and directory)
  launcher/    CLI (legacy and `eval`), REPL, nix-pbt server, flake lock, daemon and its client
  lsp/         the language server: scopes, repair of what doesn't parse, module definitions, ...
src/main/resources/nixtruffle/corepkgs/   CppNix's derivation.nix, fetchurl.nix, call-flake.nix, ...
```
