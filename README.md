# nix-truffle

A proof-of-concept interpreter for the Nix expression language, built on
Truffle/GraalVM. It has full lazy semantics, a JIT (Graal compiles thunks and
lambdas and inlines them into each other), derivations and instantiation
through the Nix daemon, fetchers (`fetchTree`, `fetchGit`, `fetchTarball`,
`fetchurl`, ...), flakes (`builtins.getFlake`, lock files), a `nix eval`-compatible
command line that takes flake references, Determinate Nix's `builtins.wasm` on GraalWasm,
a REPL, and two-way polyglot interop with other Truffle languages (JS is bundled).

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

## Installing

```sh
nix run github:yorickvp/nix-truffle -- eval nixpkgs#hello.name   # or: nix profile install ...
nix build .#default    # bin/nix-truffle on GraalVM CE (with the daemon, see below)
nix build .#native     # a native executable (see below: fast to start, slow on big evaluations)
nix build .#native-oracle  # the same with Oracle GraalVM's G1 (Linux; Oracle GraalVM is under
                       # the GraalVM Free Terms and Conditions, unfree in nixpkgs: the flake allows
                       # it for this package)
nix flake check        # builds the package and runs a few commands
```

`nix-truffle` without arguments prints its usage, and `nix-truffle COMMAND --help` a command's:

```sh
nix-truffle eval [OPTION...] [INSTALLABLE]       # like nix eval
nix-truffle repl [OPTION...] [FILE | FLAKEREF...] # like nix repl; files are loaded as with :l
nix-truffle flake lock [FLAKEREF]                # like nix flake lock
nix-truffle [OPTION...] (FILE... | -E EXPR)      # like nix-instantiate --eval --strict
```

## Building and running

```sh
nix develop            # GraalVM CE 25 + Maven (see flake.nix)
mvn -q package         # compiles, and writes target/classpath.txt
bin/nix-truffle repl                # REPL
bin/nix-truffle FILE.nix            # like nix-instantiate --eval --strict
bin/nix-truffle -E 'EXPR' [-A attr] [--arg name expr] [--argstr name string]
bin/nix-truffle --instantiate '<nixpkgs>' -A hello   # like nix-instantiate: writes .drv files
bin/nix-truffle --instantiate --read-only ...        # only compute the .drv paths
bin/nix-truffle --repeat 10 --time bench/fib.nix     # watch the JIT warm up
bin/nix-truffle eval .#nixosConfigurations.host.config.system.build.toplevel.drvPath   # like nix eval
bin/nix-truffle eval --raw nixpkgs#hello.name --json --file default.nix --expr ... --apply ...
bin/nix-truffle flake lock [FLAKEREF]                # like nix flake lock (default: .)
bin/nix-truffle --pbt-server                         # evaluator for nix-pbt (see below)
NIX_TRUFFLE_DAEMON=1 bin/nix-truffle ...             # run in a warm background daemon (see below)
bin/build-native       # native executable target/nix-truffle (see below)
tests/run.sh           # differential tests against nix-instantiate (SLOW=1 adds chromium)
bench/run.sh           # timing against nix-instantiate
```

Settings come from `nix.conf` (`$NIX_CONF_DIR`, `$XDG_CONFIG_DIRS`, `$XDG_CONFIG_HOME`),
`$NIX_CONFIG`, and `--option NAME VALUE` / `--NAME VALUE` / `--extra-experimental-features`,
as in Nix. The ones nix-truffle uses: `experimental-features` (`flakes` enables `getFlake`,
`fetchTree` on URLs and the other flake builtins), `pure-eval`, `nix-path`, `tarball-ttl`,
`flake-registry`, `use-registries`, `access-tokens`, `allow-dirty`, `warn-dirty`,
`max-call-depth`, `eval-cores` (see below), and its own `polyglot` (default `true`; `--option
polyglot false` removes the polyglot builtins, `builtins.pkl` and foreign `import` (including
of `.pkl` files), so that `builtins` looks like CppNix's).

The REPL follows `nix repl`: `x = expr` bindings, `:l <nixpkgs>`, `:lf FLAKEREF`, `:r`,
`:a`, `:p`, `:t`, tab completion of attribute paths, multi-line input, Ctrl-C to interrupt, and
errors shown inline (`d = «error: oops»;`). `nix-truffle repl ARG...` loads each file (or
`<nixpkgs>`) as with `:l`, calling functions with the `--arg`/`--argstr` arguments, and each
flake reference as with `:lf` (unlike CppNix 2.35, whose `nix repl` takes only installables);
with `--file FILE` or `--expr EXPR`, the arguments are attribute paths into it instead.

Like CppNix, calls may only nest `max-call-depth` (10000) deep: "stack overflow; max-call-depth
exceeded", which `tryEval` doesn't catch. It counts what CppNix counts (calls, the primops
behind `- * / <`, and the recursion of `==`, `deepSeq`, `toJSON` and string coercion), so the
same recursions fail, and runaway recursion fails in half a second. Evaluation runs on a
thread with a 128 MB stack (`NIX_TRUFFLE_JAVA_OPTS=-Dnixtruffle.stackMb=N`), for deep chains
of thunks, which don't count.

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

## Pkl

[Pkl](https://pkl-lang.org) is a configuration language with classes, type constraints,
defaults, and late binding, and it runs on Truffle too: nix-truffle embeds it (pkl-core, through
its Java API) on the JVM. Three directions work (see `examples/pkl`, `tests/pkl.sh`):

- **Typed configuration in Nix**: `import ./config.pkl` evaluates a Pkl module to a Nix value.
- **Nix data checked and completed by Pkl types**: `builtins.pkl` amends a Pkl module with Nix
  values. Pkl checks them against the module's types and constraints, fills in the defaults, and
  recomputes what depends on them; with `output = true`, it returns the module's rendered output
  (YAML, JSON, plist, XML, properties, ...), for typed configuration files:

  ```nix
  builtins.pkl {
    module = ./services.pkl;
    amend.services = [ { name = "web"; port = 443; } { name = "api"; } ];
  }
  # { domain = "example.org"; services = [ { name = "web"; port = 443; restart = "on-failure"; ... } ... ];
  #   urls = [ "https://web.example.org:443" "https://api.example.org:8080" ]; }
  ```

  A value that doesn't fit is an error, in Pkl's words:

  ```
  error: Pkl: Type constraint `matches(Regex("[a-z][a-z0-9-]*"))` violated.
  Value: "Web!"
  ...
  error: Pkl: Cannot find property `prot` in object of type `services#Service`.
  ...
  Did you mean any of the following?
  port
  ```

- **Pkl using nixpkgs**: `nix:` URIs in Pkl go into the `nix` attribute set given to
  `builtins.pkl { ...; nix = { inherit pkgs; }; }`. `import "nix:pkgs" as pkgs` is a module with
  a property per attribute, each `import("nix:pkgs.NAME").value`, which Pkl evaluates (and Nix
  with it) only when it is used, so `pkgs.hello.version` evaluates `hello` and nothing else.
  `read("nix:pkgs.hello")` is a value as text: a derivation's output path, JSON for sets and lists.
  Store paths that come back to Nix keep their string context, so derivations that use them
  depend on the right derivations.

`builtins.pkl` takes `module` (a path) or `text`, and optionally `amend` (with `module`),
`expression` (evaluated in the module instead of the whole module), `output`, and `nix`. Values:

| Pkl | Nix | | Nix | Pkl (as the property's type has it) |
|---|---|---|---|---|
| objects, `Mapping`, `Map` | attribute sets | | attribute sets | `new { name = ... }`, `new { ["key"] = ... }`, `Map(...)` |
| `Listing`, `List`, `Set` | lists | | lists | `new { ... }`, `List(...)`, `Set(...)` |
| `Dynamic` with only elements | a list | | derivations, paths | strings (the output path, the path) |
| `Duration`, `DataSize` | `{ value, unit }` | | strings, numbers, booleans, `null` | the same |
| `Pair` | a two-element list | | functions | an error |

- Pkl evaluates in its own Truffle context; its callbacks into Nix (`nix:`) enter nix-truffle's
  again. Calls are evaluated on the main thread (workers leave them to it, like other foreign
  code). The first one in a process takes about half a second (Pkl's standard library); then a
  call takes milliseconds, and in the daemon an amended module 60 ms.
- In pure evaluation, the files Pkl reads must be ones Nix could read, and Pkl packages, `https:`
  and environment variables are off.
- Pkl's functions can't come back (Pkl doesn't export them: such properties must be `local` or
  `hidden`), and `nix:` paths are attribute paths, so Pkl can't call Nix functions yet.
- `amend` assigns every attribute (`name = ...`), replacing the module's default rather than
  amending it.
- Native images leave Pkl out: its language needs a native-image configuration of its own.
  `builtins.pkl` and `.pkl` imports say so there.

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

## `nix-truffle eval`

`nix-truffle eval [option...] [installable]` works like CppNix's `nix eval`
(`launcher/EvalCommand.java`, `builtins/CliEval.java`):

- **Installables** are flake references with an optional attribute path: `.`, `.#foo`,
  `nixpkgs#hello.name`, `github:owner/repo/ref#x`, `path:/some/dir?dir=sub#y`,
  `git+file:///repo#z`. The default is `.`. A fragment `foo` is looked up as
  `packages.<system>.foo`, `legacyPackages.<system>.foo`, then `foo` in the flake's outputs;
  `.foo` means exactly `foo`; no fragment means `packages.<system>.default` or
  `defaultPackage.<system>`. With `--file FILE` or `--expr EXPR`, the installable is an
  attribute path into that (list indices allowed, functions called with `--arg`/`--argstr`
  on the way).
- **Output**: the value as `nix eval` prints it (a port of CppNix's `print.cc`: derivations as
  `«derivation …drv»`, `«repeated»`, errors inside the value as `«error: …»`), `--json`
  (`--pretty` when stdout is a terminal), `--raw`, `--apply EXPR`, `--write-to PATH`.
  The store paths the output refers to (e.g. a printed `drvPath`, with its closure) are
  written to the store afterwards, unless `--read-only`.
- **Evaluation is pure** unless `--impure` or `--file` is given, like the new CLI: only the
  flake's source, its inputs and the paths evaluation added to the store can be read, the
  lookup path is empty, `builtins.currentSystem` and `currentTime` don't exist, `getEnv` is
  empty, and fetchers only take locked inputs.
- **Lock files** are handled like `nix eval` does: a changed lock file is written (`flake.lock`
  in the flake's directory, `git add`ed in a Git work tree), unless `--no-write-lock-file`.
  `--override-input PATH REF`, `--update-input PATH`, `--recreate-lock-file`,
  `--no-update-lock-file`, `--reference-lock-file`, `--output-lock-file`,
  `--commit-lock-file`, `--inputs-from REF` and `--override-flake FROM TO` are supported.
- Settings flags work as in Nix: `--option NAME VALUE`, `--extra-experimental-features X`,
  `--NAME VALUE`, `--[no-]pure-eval` and so on; `-I` too. Logging and building flags
  (`--show-trace`, `-L`, `--no-eval-cache`, `-j`, ...) are accepted and ignored.

Error messages follow CppNix's, since `nix eval` prints them inside values: type errors are
`expected a set but found an integer: 1`, failed assertions show the condition as CppNix's
parser desugars it (`assertion '((__sub x 1) == 2)' failed`) and, for `==`, where the two
sides differ. `tests/eval-cli.sh` runs 195 command lines through both `nix eval` (CppNix
2.35) and `nix-truffle eval` and compares their output, exit status and lock files, including
the evaluation of a real NixOS system flake.

## `builtins.wasm`

Determinate Nix's `builtins.wasm` (behind the `wasm-builtin` experimental feature) runs
WebAssembly modules; here they run on [GraalWasm](https://www.graalvm.org/webassembly/)
(`builtins/WasmBuiltin.java`, a port of Determinate Nix's `libexpr/primops/wasm.cc`):

```nix
builtins.wasm { path = ./fib.wasm; function = "fib"; } 40          # a plain module
builtins.wasm { path = ./plugin.wasm; } { some = "argument"; }     # a WASI module
builtins.wasm { wat = builtins.readFile ./fib.wat; function = "fib"; } 40
```

Nix values are `u32` IDs that the module manipulates through the host functions of the `env`
module (`get_type`, `make_int`, `copy_string`, `make_attrset`, `call_function`, `make_app`,
`read_file`, ...), with the same bounds checks. Every call gets a fresh instance of the
compiled module. A module that imports from `wasi_snapshot_preview1` runs its `_start` with
the argument's ID as `argv[1]` and returns with `return_to_nix`; nix-truffle implements the WASI
functions itself, like wasmtime's default configuration: no environment, empty stdin, no
preopened directories, and stdout/stderr become warnings, one per line. `wat` sources are
compiled with `wat2wasm` from wabt (in the dev shell), since GraalWasm reads only the binary
format.

All the examples of [nix-wasm-rust](https://github.com/DeterminateSystems/nix-wasm-rust) pass
their tests (YAML, INI, grep, Mandelbrot, and QuickJS running JavaScript from Nix through
WASI), and so do Determinate Nix's own `wasm.sh` cases (`tests/wasm.sh`). Compared with
Determinate Nix 3.22 on edge cases, two things differ on purpose: `make_attrset` with a repeated
name keeps the last one (Determinate Nix makes a set with the name twice), and `copy_attrset`
lists attributes by name (Determinate Nix in interning order, with a FIXME to sort them).
Instantiating a module costs more with GraalWasm (tens of microseconds for a typical Rust
module, which allocates a megabyte of memory) than with wasmtime's pooling allocator, which
shows in code that calls a Wasm function very often.

## Speed: the daemon and native images

Most of a one-off run is warm-up. HotSpot is compiling the interpreter itself, then Truffle
is compiling nixpkgs, and both are thrown away when the process exits. Very little of the code
runs often: a NixOS evaluation makes 490k thunk and lambda bodies, of which 20% ever run and
1.5k get compiled. So the translator only checks bodies for errors at first, and builds them
when they first run (`nodes/LazyCode.java`), keeping their syntax trees until then (with
exact-size lists, and one string per identifier for all files). And `bin/nix-truffle` uses
compact object headers, which take a third off the memory of a large evaluation. The language uses
`ContextPolicy.SHARED`, so parsed and compiled code can be shared by all contexts of an engine:
code depends on a context only through its `GlobalScope` (the names in its base environment),
and `NixLanguage` keeps parsed files by path and contents. Two things build on that.

**The daemon.** With `NIX_TRUFFLE_DAEMON=1`, `bin/nix-truffle` starts a small client JVM
(about 50 ms). The client hands the command line, environment and current directory to a
background daemon over a Unix socket in `$XDG_RUNTIME_DIR/nix-truffle` (or
`~/.cache/nix-truffle/daemon`), starting the daemon first if needed.
- Every command gets a fresh context, so results don't depend on what ran before. But files
  are parsed once (and again when their contents change), and compiled code stays warm.
- A daemon exits when its build changes (the next command then starts a new one), after
  three idle hours (`NIX_TRUFFLE_DAEMON_IDLE`, in seconds), or on `nix-truffle daemon stop`.
  `nix-truffle daemon status` shows it.
- ^C cancels the command in the daemon too. The REPL and `--pbt-server` run in the client's
  own JVM.

**Native images.** `bin/build-native` builds `target/nix-truffle` with native-image. The
configuration is in `src/main/resources/META-INF/native-image`, with metadata recorded by the
tracing agent while running the tests. A native image starts in milliseconds.
- GraalVM CE's native images only have the serial GC, which copies the live set of a big
  evaluation single-threaded, again and again. So they suit small evaluations only.
- Oracle GraalVM adds G1 (`--gc=G1`, on Linux), which the flake's `native-oracle` package uses,
  and profile-guided optimization: build with `--pgo-instrument`, run a few evaluations with
  `-XX:ProfilesDumpFile=F.iprof`, then build with `--pgo=F.iprof,...`.
- Oracle GraalVM also has auxiliary engine caching, which saves parsed and compiled code to a
  file. It needs the serial GC:
  - build with `-H:+AuxiliaryEngineCache`;
  - store with `-Dpolyglot.engine.AllowExperimentalOptions=true -Dpolyglot.engine.CacheStore=F`;
  - load with the same flags with `CacheLoad=F`, plus `-XX:AuxiliaryImageBytes=N`, where N is at
    least the size of F.
- Oracle GraalVM 25.0 needs the Truffle artifacts of its version, and `truffle-enterprise`
  instead of `truffle-runtime`: `mvn -Poracle` (25.0.3, nixpkgs' `graalvm-oracle`). The default
  profile's 25.3 ones need GraalVM CE 25.3.

On this machine (32 cores):

| | `1+1` | nixpkgs `hello.drvPath` | minimal NixOS `toplevel.drvPath` | a desktop NixOS config |
|---|---|---|---|---|
| Lix 2.94 | 0.02 s | 0.22 s | 2.5 s | 12.6 s |
| JVM, one run (GraalVM CE 25.3) | 0.30 s | 1.0 s | 3.2 s | 4.7 s |
| daemon, warm | 0.065 s | 0.26 s | 1.6–2.0 s | 2.7 s, also after an edit |
| native, GraalVM CE (serial GC, `.#native`) | 0.005 s | 0.53 s | 11 s | 20 s |
| native, Oracle GraalVM, G1 (`.#native-oracle`) | 0.010 s | 0.56 s | 2.8 s | 6.1 s |
| native, Oracle GraalVM, serial GC + engine cache of `hello` | | 0.44 s | | |

On the JVM, Oracle GraalVM's compiler makes no difference to one run (1.06 s and 4.5 s): what
it waits for is the interpreter warming up. Turning off Truffle compilation altogether doesn't
make the minimal NixOS evaluation any slower either (4.1 s).

### Parallel evaluation

With `eval-cores` (as in Determinate Nix: 1 evaluates on one thread; 0, the default here, uses
a thread per core, but at most one per GB of heap), evaluation uses other threads where a
thread is about to force many values anyway
(`runtime/Parallel.java`). It offers them to idle workers, and then goes through them in order,
finding them evaluated or waiting for the worker evaluating one. Those places are:

- the lists and attribute sets that `nix-truffle eval` prints;
- builtins that force every element of a list (`concatLists`, `sort`, `listToAttrs`, `catAttrs`,
  `concatStringsSep`, string coercion of lists) or every application of a function to them
  (`filter`, `concatMap`, `partition`, `groupBy`);
- the attributes of a derivation, which reach the derivations it depends on.

In a NixOS configuration, those are where independent parts come together. For example, the
systemd module's `warnings` concatenates a list per service, which evaluates every service's
configuration; on one of the test machines, that includes a microvm guest's whole system and the
man page cache.

Each such place adds one batch of values. Idle workers take the oldest batch first, since
those are the biggest parts, and claim values from its end with an atomic counter. Values that
are already evaluated by then cost a check.

- A thunk is claimed with a compare-and-swap, and holds the thread evaluating it. A thread that
  finds its own thunk running has an infinite recursion, as before; one that finds another
  thread's waits for it. Threads that each wait for a thunk the next one is evaluating are an
  infinite recursion too (one thread would have found its own thunk), and report it as one.
- Workers have no side effects out of order: a worker that reaches `getFlake`, Wasm or another
  language gives up its task (its thunks become pending again, and the thread that needs them
  evaluates them), and so does one that hits an error, which that thread then hits too, as a
  sequential evaluation would. Fetching is serialized, with its arguments evaluated first.
  Traces and warnings from workers come out as they happen.
- Workers start one call level deeper than the thread that offered the values. A worker that
  reaches `max-call-depth` gives the value up, so the same recursions fail. The exception is a
  value that a sequential evaluation would first have reached from deeper down: the worker
  succeeds where that evaluation could have hit the limit.
- Until the first worker starts, compiled code assumes one thread (a Truffle assumption):
  sequential evaluation costs the same as before.

Evaluating all seven NixOS configurations of a flake in one command (`nix-truffle eval --json
.#nixosConfigurations --apply 'builtins.mapAttrs (n: c: c.config.system.build.toplevel.drvPath)'`)
takes 8.7 s with the default (32 threads here; 9.4 s with `--option eval-cores 8`) instead of
26.4 s on one thread (Lix: 35.6 s). The largest of them alone takes 4.7 s instead of 9.6 s
cold, and 2.7 s instead of 6.8 s warm in the daemon. The store is used without a lock
(computing store paths means hashing sources and derivations), and peak memory grows with the
work in progress: 9.5 GB for all seven (8.0 GB with 8 threads), against 7.1 GB on one.
`-Dnixtruffle.parallelStats=true` prints what the workers did and how long threads waited.

## What's there and what isn't

- **Supported:** the whole expression language (strings with interpolation, indented strings,
  paths including interpolated ones, `<nixpkgs>` via `NIX_PATH` incl. `flake:` entries, `rec`,
  `let`, `inherit (e)`, nested attribute paths, dynamic attributes, formals with defaults, `...`
  and `@`, `with`, `assert`, all operators, `__functor`, `or`, `?`), and all builtins nixpkgs
  needs to instantiate chromium and a NixOS test: derivations, string context,
  `path`/`filterSource`, `toFile`, `placeholder`, `fromTOML`, `fromJSON`/`toJSON`, `toXML`,
  `__curPos`, regexes, `genericClosure`, `compareVersions`, `hashString`/`hashFile`, and so on;
  the fetchers and flake builtins above; `builtins.pkl` and `.pkl` imports (see Pkl); and the
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
  above), and there is no evaluation cache.
- **Known divergence:** CppNix keeps attribute sets in the order their names were first
  interned (well-known names like `drvPath` first, then in parse order), nix-truffle sorts
  them by name. Only which error comes first differs, when forcing all attributes
  (`deepSeq`, `==`) hits several of them.

## Tests and numbers

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
`nix eval` (see above; `NIXPKGS=1` adds cases that fetch nixpkgs); run it with
`NIX_CONFIG="eval-cores = 8"` too. `NIX=... tests/parallel.sh` evaluates expressions that
workers share (cycles, errors, `max-call-depth`) with `eval-cores = 8`, several times each, and
compares them with CppNix. `tests/repl.sh` drives `nix-truffle repl` (files, flakes, `:l`, `:lf`,
`:r`). `tests/pkl.sh` tests Pkl from Nix. `tests/wasm.sh` tests
`builtins.wasm`, and with `PLUGINS`, `WASI` and `NIX_WASM_RUST` set runs nix-wasm-rust's test
suite too.

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

`bench/run.sh` on this machine (Lix 2.94 vs GraalVM CE 25.3):

| benchmark | nix-instantiate | nix-truffle, one run incl. JVM start | nix-truffle warm |
|---|---|---|---|
| `fib.nix` (fib 32, one thunk per call) | 0.59 s | 0.73 s | 84 ms |
| `lazy.nix` (lazy prime stream, attrset fixpoint) | 0.48 s | 0.80 s | 95 ms |

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
src/main/resources/nixtruffle/corepkgs/   CppNix's derivation.nix, fetchurl.nix, call-flake.nix, ...
```
