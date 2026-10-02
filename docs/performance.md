# Performance: the daemon, native images and parallel evaluation

Most of a one-off run is warm-up. HotSpot is compiling the interpreter itself, then Truffle
is compiling nixpkgs, and both are thrown away when the process exits. Very little of the code
runs often: a NixOS evaluation makes 490k thunk and lambda bodies, of which 20% ever run and
1.5k get compiled. So the translator only checks bodies for errors at first, and builds them
when they first run (`nodes/LazyCode.java`), keeping their syntax trees until then (with
exact-size lists, and one string per identifier for all files). And `bin/nix-truffle` uses
compact object headers, which take a third off the memory of a large evaluation. The language uses
`ContextPolicy.SHARED`, so parsed and compiled code can be shared by all contexts of an engine:
code depends on a context only through its `GlobalScope` (the names in its base environment),
and `NixLanguage` keeps parsed files by path and contents. Two things build on that: the daemon and native images.

## The daemon

With `NIX_TRUFFLE_DAEMON=1`, `nix-truffle` starts a small client JVM (about 50 ms). The client hands the command line, environment and current directory to a
background daemon over a Unix socket in `$XDG_RUNTIME_DIR/nix-truffle` (or
`~/.cache/nix-truffle/daemon`), starting the daemon first if needed.

- Every command gets a fresh context, so results don't depend on what ran before. But files
  are parsed once (and again when their contents change), and compiled code stays warm.
- A daemon exits when its build changes (the next command then starts a new one), after
  three idle hours (`NIX_TRUFFLE_DAEMON_IDLE`, in seconds), or on `nix-truffle daemon stop`.
  `nix-truffle daemon status` shows it.
- ^C cancels the command in the daemon too. The REPL and `--pbt-server` run in the client's
  own JVM.

## Native images

`bin/build-native` builds `target/nix-truffle` with native-image (the flake: `.#native` and
`.#native-oracle`). The configuration is in `src/main/resources/META-INF/native-image`, with metadata recorded by the
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

## Numbers

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

## Parallel evaluation

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
