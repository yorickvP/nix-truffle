# nix-truffle

A proof-of-concept interpreter for the Nix expression language, built on
Truffle/GraalVM. It has full lazy semantics, a JIT (Graal compiles thunks and
lambdas and inlines them into each other), derivations and instantiation
through the Nix daemon, a REPL, and two-way polyglot interop with other Truffle
languages (JS is bundled). There are no fetchers.

```
$ bin/nix-truffle --instantiate '<nixpkgs>' -A chromium
/nix/store/30509ibhwkm7i31bam9i5px7n0bvvv27-chromium-154.0.8037.57.drv
$ nix-instantiate '<nixpkgs>' -A chromium
/nix/store/30509ibhwkm7i31bam9i5px7n0bvvv27-chromium-154.0.8037.57.drv
```

A `.drv` path hashes its entire build closure. Matching Lix on chromium means
all 4,135 derivations and 570 sources in it come out byte-identical: ATerm
serialization, `hashDerivationModulo`, string context, NAR hashing of local
sources, structured attrs JSON, `fromTOML`, and so on. It takes 2.7 s against
Lix's 2.3 s, JVM startup and JIT warm-up included.

NixOS evaluates too. `-A nixosTests.cosmic` (a full NixOS system, its VM and
the test driver: 7,083 paths, 6,341 derivations) matches Lix, in 5.2 s against
5.1 s. So do the test's `driver`, `driverInteractive`, and the machine's
`system.build.toplevel` and `system.build.vm`.

```
$ bin/nix-truffle -E 'let fib = n: if n < 2 then n else fib (n - 1) + fib (n - 2); in fib 32'
2178309
$ bin/nix-truffle examples/polyglot.nix
{ callback = 42; described = "hello-2.12 (2 deps)"; fib = 6765; ... }
```

## Building and running

```sh
nix develop            # GraalVM CE 25 + Maven (see flake.nix)
mvn -q package         # compiles, and writes target/classpath.txt
bin/nix-truffle                     # REPL
bin/nix-truffle FILE.nix            # like nix-instantiate --eval --strict
bin/nix-truffle -E 'EXPR' [-A attr] [--arg name expr] [--argstr name string]
bin/nix-truffle --instantiate '<nixpkgs>' -A hello   # like nix-instantiate: writes .drv files
bin/nix-truffle --instantiate --read-only ...        # only compute the .drv paths
bin/nix-truffle --repeat 10 --time bench/fib.nix     # watch the JIT warm up
tests/run.sh           # differential tests against nix-instantiate (SLOW=1 adds chromium)
bench/run.sh           # timing against nix-instantiate
```

The REPL follows `nix repl`: `x = expr` bindings, `:l <nixpkgs>`, `:a`, `:p`, `:t`,
tab completion of attribute paths, multi-line input, Ctrl-C to interrupt, and errors
shown inline (`d = «error: oops»;`).

Evaluation runs on a thread with a 128 MB stack (`NIX_TRUFFLE_JAVA_OPTS=-Dnixtruffle.stackMb=N`).
That allows recursion around 500k deep. Lix stops at 10k (`max-call-depth`).
Runaway recursion takes about 15 s to fail, because every GC scans the deep stack.

## How laziness works

The design follows [thc](https://github.com/ekmett/thc), Edward Kmett's Haskell-on-Truffle
(`Thunk.java`, `Force.java`, `DispatchThunkTarget.java`, `docs/thunk-updates.md` there).

- **A thunk is a call target plus a captured frame** (`runtime/Thunk.java`). Every expression
  that needs suspending gets its own `RootNode`. The thunk holds that root's `RootCallTarget`,
  the enclosing `MaterializedFrame`, and a state: *pending*, *blackhole* or *done*.
  Re-entering a blackholed thunk raises `infinite recursion encountered`. On completion the
  thunk keeps only the answer and drops the target and frame so they can be collected. If
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
  strict variable read forces a thunk, the answer replaces the thunk in the frame slot, so the
  next read skips the thunk entirely. Bindings are immutable, so the program can't tell. The
  same is done for attrset values and list elements.
- **Thunks are only created when needed** (`Translator.lazy`, like CppNix's `maybeThunk` and
  thc's "already evaluated" proofs). Literals, lambdas and references to lexical variables are
  never wrapped: a variable in a lazy position shares the binding's existing thunk. The
  exception is a reference to another member of the same `let`/`rec`/formals group, whose slot
  may not be written yet (`let a = b; b = 1;`).
- **Frames and scoping are resolved statically.** Every lambda body and every thunk body is
  its own root. `arguments[0]` is the lexically enclosing frame and variables are
  `(depth, slot)`. `let`, `rec`, `with` and formals just allocate slots in the enclosing root's
  frame. Nix has no loops, so a node runs at most once per activation, and one slot per
  binding is enough. Unbound names fall back to the enclosing `with` scopes at runtime (after
  builtins, as in Nix).
- **Builtins that build lazy structures** (`map`, `genList`, `mapAttrs`, ...) create thunks
  whose target is a shared "apply f to x" root, so every thunk goes through the same `ForceNode`
  machinery.
- **Attrsets use a shared key array as a cheap shape.** An attrset literal sorts its keys once
  at translation time and shares that array with every set it creates, so selection
  (`nodes/SelectStepNode.java`) caches the attribute index per key-array identity. Formals
  binding does the same.

## Polyglot / foreign import

Nix → other languages:

| | |
|---|---|
| `builtins.polyglotEval "js" "(x, y) => x + y"` | evaluate source in any installed Truffle language |
| `import ./lib.js` | `import` of a non-`.nix` file evaluates it in its language (by extension) |
| `builtins.polyglotImport "name"` / `polyglotExport "name" v` | polyglot bindings |

Foreign values are normalized at the boundary: numbers, strings, booleans and null become Nix
values, and other objects stay opaque. `obj.member` reads members (or hash entries, e.g. Python
dicts), foreign arrays work as lists, and applying a foreign function passes **all** arguments
of the application in one call, so `add 40 2` calls `add(40, 2)`.

Other languages → Nix: Nix values are interop objects. Attrsets have members, lists have array
elements, and functions are executable with curried application. Members and elements are
forced only when read, so a JS function can take a set with a `throw` in it and never trip
over it. See `examples/polyglot.nix` (Nix ⇄ JS) and `examples/Embed.java` (Java host):

```sh
java -cp "target/classes:$(cat target/classpath.txt)" examples/Embed.java
```

## Derivations and the store

- **String context.** Strings carry the store paths they refer to. Context-free strings stay
  plain Java `String`s; strings with context are `NixString`s. Coercion follows CppNix:
  interpolation copies paths to the store, derivation attributes use "coerce more", and the
  string builtins propagate context.
- **`derivationStrict`** (`builtins/StoreBuiltins.java`) is a port of CppNix's. It builds env
  vars or `__structuredAttrs` JSON (with nlohmann's float formatting), collects inputs from
  context (a `drvPath`'s context pulls in its whole closure), and computes fixed-output paths
  (any hash encoding) or input-addressed paths via `hashDerivationModulo` over the ATerm
  (`store/Derivation.java`). `derivation` and `<nix/fetchurl.nix>` are Lix's own corepkgs
  files, extracted verbatim.
- **Store paths** are computed like libstore's (`store/StorePaths.java`, `store/Hash.java`
  with Nix base32, `store/Nar.java` for sources, including `builtins.path` filters). Paths go
  into a per-evaluation registry; nothing touches the store while evaluating.
- **Instantiation** (`--instantiate`) writes the closure of every `.drv` (sources, `toFile`
  texts, input derivations) to the real store. It uses a small worker-protocol client
  (`store/DaemonClient.java`, protocol 1.35, content-addressed `AddToStore`, so untrusted users
  can do it). The daemon computes each path itself, and a mismatch with ours is an error.

## What's there and what isn't

- **Supported:** the whole expression language (strings with interpolation, indented strings,
  paths including interpolated ones, `<nixpkgs>` via `NIX_PATH` incl. `flake:` entries, `rec`,
  `let`, `inherit (e)`, nested attribute paths, dynamic attributes, formals with defaults, `...`
  and `@`, `with`, `assert`, all operators, `__functor`, `or`, `?`), and all builtins nixpkgs
  needs to instantiate chromium and a NixOS test: derivations, string context,
  `path`/`filterSource`, `toFile`, `placeholder`, `fromTOML`, `fromJSON`/`toJSON`, `toXML`,
  `__curPos`, regexes, `genericClosure`, `compareVersions`, `hashString`/`hashFile`, and so on.
- **Not supported:** fetchers (`fetchurl`, `fetchTarball`, `fetchGit`, `fetchTree`),
  `scopedImport`, content-addressed or impure derivations, import-from-derivation,
  URL literals, `let { }`. Unsupported builtins exist, so code that mentions them still resolves
  (Nix resolves variables statically), but they throw when called.
  `builtins.unsafeGetAttrPos` returns null, which only changes error messages and option
  declaration positions.
- **Approximations:** regexes use `java.util.regex` (POSIX classes translated), strings are
  Java strings (`substring` indexes UTF-16 units, not bytes), and error messages have a location
  and a derivation trace but no full Nix-style trace. Evaluation is single-threaded.

## Tests and numbers

`tests/run.sh` evaluates every `tests/cases/*.nix` with both `nix-instantiate --eval --strict`
and nix-truffle and diffs the output (errors are compared as "error"). The cases include
`derivations.nix`, which covers most derivation features. The runner also:

- checks the polyglot example against a golden file;
- instantiates `tests/instantiate.nix` with fresh, salted paths (nix-truffle writes them first,
  then `nix-instantiate` has to agree);
- when `<nixpkgs>` is available, diffs `examples/nixpkgs-lib.nix` (including `evalModules`) and
  the `.drv` paths of `hello` and, with `SLOW=1`, `chromium` and `nixosTests.cosmic`.

Currently all 36 checks pass against Lix 2.94 (with `SLOW=1`).

`bench/run.sh` on this machine (Lix 2.94 vs GraalVM CE 25.3):

| benchmark | nix-instantiate | nix-truffle, one run incl. JVM start | nix-truffle warm |
|---|---|---|---|
| `fib.nix` (fib 32, one thunk per call) | 0.61 s | 0.62 s | 63 ms |
| `lazy.nix` (lazy prime stream, attrset fixpoint) | 0.47 s | 0.70 s | ~100–165 ms |

## Layout

```
src/main/java/nixtruffle/
  parser/      hand-written lexer + recursive-descent parser → Expr records
  Translator   scope resolution, slots, where thunks go → Truffle nodes
  nodes/       ForceNode, ReadVarNode, ApplyNode/DispatchNode, SelectStepNode, operators, ...
  runtime/     Thunk, NixAttrs, NixList, NixString (context), NixLambda, interop (Foreign), printers
  builtins/    primops, derivations and context (StoreBuiltins), JSON, TOML, version comparison
  store/       hashes, store paths, NAR, Derivation (ATerm), store registry, daemon client
  launcher/    CLI and REPL
src/main/resources/nixtruffle/corepkgs/   Lix's derivation.nix and fetchurl.nix
```
