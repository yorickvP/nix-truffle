# Command line

`nix-truffle` without arguments prints its usage, and `nix-truffle COMMAND --help` a command's:

```sh
nix-truffle eval [OPTION...] [INSTALLABLE]       # like nix eval
nix-truffle repl [OPTION...] [FILE | FLAKEREF...] # like nix repl; files are loaded as with :l
nix-truffle flake lock [FLAKEREF]                # like nix flake lock (default: .)
nix-truffle lsp                                  # a language server, see language-server.md
nix-truffle [OPTION...] (FILE... | -E EXPR)      # like nix-instantiate --eval --strict
```

More of the legacy form:

```sh
nix-truffle FILE.nix                             # like nix-instantiate --eval --strict
nix-truffle -E 'EXPR' [-A attr] [--arg name expr] [--argstr name string]
nix-truffle --instantiate '<nixpkgs>' -A hello   # like nix-instantiate: writes .drv files
nix-truffle --instantiate --read-only ...        # only compute the .drv paths
nix-truffle --repeat 10 --time bench/fib.nix     # watch the JIT warm up
nix-truffle --pbt-server                         # evaluator for nix-pbt (see development.md)
NIX_TRUFFLE_DAEMON=1 nix-truffle ...             # run in a warm background daemon (see performance.md)
```

## Settings

Settings come from `nix.conf` (`$NIX_CONF_DIR`, `$XDG_CONFIG_DIRS`, `$XDG_CONFIG_HOME`),
`$NIX_CONFIG`, and `--option NAME VALUE` / `--NAME VALUE` / `--extra-experimental-features`,
as in Nix. The ones nix-truffle uses: `experimental-features` (`flakes` enables `getFlake`,
`fetchTree` on URLs and the other flake builtins), `pure-eval`, `nix-path`, `tarball-ttl`,
`flake-registry`, `use-registries`, `access-tokens`, `allow-dirty`, `warn-dirty`,
`max-call-depth`, `eval-cores` (see [parallel evaluation](performance.md#parallel-evaluation)), and its own `polyglot` (default `true`; `--option
polyglot false` removes the polyglot builtins, `builtins.pkl` and foreign `import` (including
of `.pkl` files), so that `builtins` looks like CppNix's).

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

## `nix-truffle repl`

The REPL follows `nix repl`: `x = expr` bindings, `:l <nixpkgs>`, `:lf FLAKEREF`, `:r`,
`:a`, `:p`, `:t`, tab completion of attribute paths, multi-line input, Ctrl-C to interrupt, and
errors shown inline (`d = «error: oops»;`). `nix-truffle repl ARG...` loads each file (or
`<nixpkgs>`) as with `:l`, calling functions with the `--arg`/`--argstr` arguments, and each
flake reference as with `:lf` (unlike CppNix 2.35, whose `nix repl` takes only installables);
with `--file FILE` or `--expr EXPR`, the arguments are attribute paths into it instead.

## Call depth

Like CppNix, calls may only nest `max-call-depth` (10000) deep: "stack overflow; max-call-depth
exceeded", which `tryEval` doesn't catch. It counts what CppNix counts (calls, the primops
behind `- * / <`, and the recursion of `==`, `deepSeq`, `toJSON` and string coercion), so the
same recursions fail, and runaway recursion fails in half a second. Evaluation runs on a
thread with a 128 MB stack (`NIX_TRUFFLE_JAVA_OPTS=-Dnixtruffle.stackMb=N`), for deep chains
of thunks, which don't count.
