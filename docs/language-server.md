# Language server

`nix-truffle lsp` is a language server for Nix (stdio; `nixtruffle/lsp`) that knows Nix the way
the evaluator does. For a flake's NixOS and home-manager modules it finds the configuration that
imports the file by itself, so completion, hover and diagnostics of options need no settings.

## Setup

Run `nix-truffle lsp` for files of type `nix`. The workspace (the root the client gives) is the
flake: its directory should have a `flake.nix`. Emacs (eglot):

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs '(nix-mode . ("nix-truffle" "lsp"))))
```

Neovim (0.11):

```lua
vim.lsp.config('nix_truffle', {
  cmd = { 'nix-truffle', 'lsp' },
  filetypes = { 'nix' },
  root_markers = { 'flake.nix', '.git' },
})
vim.lsp.enable('nix_truffle')
```

Use the JVM package (`.#default`) rather than a native one: it starts slower, but a language
server runs for long, and the JVM evaluates big configurations two to three times faster (see
[below](#native-executables)).

## What it does

### Without evaluating

These come from nix-truffle's parser and scoping, which binds names as CppNix does: lexical
bindings, then globals, then `with`.

- syntax errors and undefined variables as errors, unused `let` bindings and arguments greyed
  out;
- definitions and references of variables, the files of path literals (`./dir` is its
  `default.nix`), highlight, an outline (document symbols);
- rename of `let` bindings and `x:` arguments (not of what other code names, like a function's
  attribute arguments, a set's attributes or inherited names);
- hover, and completion of the names in scope and keywords; builtins with their signature and
  documentation (CppNix's, from its `nix __dump-language`, LGPL-2.1-or-later: `bin/builtin-docs`
  makes `src/main/resources/nixtruffle/lsp/builtins.json`).

A text that doesn't parse (being typed) is repaired where the parser stops (`;`, `null;`, a
closing bracket, ...) so that all of this goes on for the rest of it, and each syntax error on
the way is reported.

### Evaluating

For what's after a `.`, names from `with`, and attribute names in modules, the expression is
evaluated as it would be at the cursor, in the scopes around it (`let`, recursive sets, `with`),
with the file's functions applied to what they would likely get. Only what the cursor needs is
evaluated: `pkgs.hel` completes in 0.4 s from a cold start, NixOS options in about a second,
later ones at once.

- completion of attributes (`pkgs.`, `lib.strings.`, `cfg.` with `cfg = config.services.nginx`,
  a local set's), of names from `with` (`with pkgs; [ hel`), and of NixOS options where a module
  sets them, through submodules (`services.nginx.virtualHosts."x".locations."/".proxyP`),
  `config = mkIf ... { ... }` and `mkMerge`;
- completion of a call's argument set (`fetchFromGitHub { ow`: the function's named arguments,
  required ones first, not those set already) and of an option's value (`mode = "`: an enum's
  values, `true`/`false`, `null`);
- details of an item, and hover: an option's type, description and default; a package's name,
  description, homepage and licenses; a function's arguments and doc comment (RFC 145 `/** */`,
  as nixpkgs' lib has, or `#` lines);
- diagnostics of a module's definitions (when it is opened and saved):
  - options that don't exist (`no option services.openssh.enabel; did you mean enable?`);
  - values that the module system's merge rejects for their option's type, each definition's
    value evaluated in its scope;
  - conflicts with the configuration's other definitions of the option (`The option
    networking.hostName has conflicting definition values`): those of the other files, as last
    evaluated, at the priority they won by; for a definition in a submodule, at the submodule's
    option (`users.users` for `users.users.x.uid`);
- inlay hints: the version of the packages a file names (`pkgs.hello` 2.12.3, `with pkgs;
  [ htop ]` 3.5.3), and in a module the default of the options it defines, where that's short,
  not empty (null, false, `[ ]`, ...) and not what's written (`peer-port = 54735; default:
  51413`); asked for again (`workspace/inlayHint/refresh`) once the import index is ready and
  after a reload;
- quick fixes (code actions): the name a "did you mean" meant, the `;` (or bracket, quote) a
  syntax error misses;
- go to definition of attributes: a package's `meta.position`, an option's declarations, a
  function's own position, else the attribute's.

Formatting runs the `formatter` setting's command (default `nixfmt`), which reads the document
on its standard input (the file isn't written).

## What a file is evaluated with

Hover says what it was evaluated with.

- **A module** (a file a configuration imports, or a function of `config`, `options` or `pkgs`
  and `...`) gets a configuration's `config`, `options`, `pkgs` and `lib`: NixOS's
  (`nixosConfigurations`), or home-manager's (`homeConfigurations`, or a user's of home-manager
  as a NixOS module, `home-manager.users.alice = import ./alice.nix`: its submodule's
  evaluation, with `osConfig`). Which configuration:
  1. one that imports the file, and among those one named in the file's path
     (`machines/frumar/...`);
  2. else one named in its path (home-manager's for a path with `home-manager` or `home.nix`);
  3. else the first.

  What each configuration imports is recorded by listing its options, in the background from
  the start: a few seconds for seven machines and a home. It's kept in
  `~/.cache/nix-truffle/lsp`; one of an earlier source of the flake is used at once, and
  evaluated again a configuration at a time between requests. Configurations are evaluated
  without the check that what is defined is declared, which one being edited often fails.
- **A package of the flake** (a file its `packages.${system}.<name>` or `legacyPackages`', named
  after its directory or itself, calls) gets the arguments it is called with, recorded by
  evaluating that package (a scope of its own, `makeScope` or `callPackage` with arguments, gives
  arguments nixpkgs hasn't).
- **Anything else** (packages, overlays, ...) gets the package set: the flake's
  `legacyPackages.${system}`, else nixpkgs with the flake's overlays, else its `nixpkgs` input,
  else `<nixpkgs>`. Names follow callPackage's rules (then `python3Packages`'); an overlay's
  `final`/`self` is the package set and `prev`/`super` upstream nixpkgs; the flake's `self` and
  inputs are there too.

The flake is the one `nix build .` would see: in a git repository the files git tracks, changes
included (`git+file`; a new file needs `git add`), copied to the store when it's evaluated.
Locations in that copy are given as the workspace's files. Saving `flake.nix`, `flake.lock` or a
module with options or imports evaluates it all again, in a new context (files, fetched flakes
and copies in the store as they are now); saving another file only diagnoses it again.

Evaluation runs on its own thread, one request at a time, on the document as it was when the
request came; everything else is answered meanwhile. An evaluation is interrupted when it takes
longer than `evalTimeout` (seconds, default 10) or its request is cancelled. Evaluation errors
go to the client's log.

## Settings

Settings (`initializationOptions`, or `workspace/didChangeConfiguration`'s `nix-truffle`) are
expressions in a scope with the workspace's `flake`, its `inputs`, `system` and `upstream` (its
`nixpkgs` input's packages, or `<nixpkgs>`):

```json
{
  "nixpkgs": "flake.legacyPackages.${system}",
  "nixos": "flake.nixosConfigurations.frumar",
  "home": "flake.homeConfigurations.x86_64-linux",
  "configurations": { "nixos/roles/**": "flake.nixosConfigurations.frumar" },
  "evalTimeout": 10,
  "formatter": ["nixfmt"]
}
```

`nixpkgs` is the package set (`"upstream"`, or `"import inputs.nixpkgs { inherit system; overlays
= [ flake.overlays.default ]; }"`), `nixos` and `home` the configuration for every NixOS or
home-manager module, `configurations` one for the files a glob matches. A configuration is
anything with `options`, `config` and `pkgs` (and `args`, more arguments for its modules), so
one can be made by hand:

```json
"home": "let os = flake.nixosConfigurations.frumar; in { options = os.options.home-manager.users.type.getSubOptions [ ]; config = os.config.home-manager.users.yorick; pkgs = os.pkgs; args.osConfig = os.config; }"
```

## Native executables

The language server works the same in the native executables (`tests/lsp.py` with `TRUFFLE=` the
executable), which answer from the start (initialize in 0.02 s rather than 0.35), but evaluate
big things slower. The NixOS options of seven machines (one evaluation thread, as the server
has):

| | JVM | native-oracle (G1) | native (serial GC) |
|---|---|---|---|
| options of seven machines | 2.8 s | 3.7 s | 7.6 s (4.6 s collecting) |
| what the dotfiles' eight configurations import | 5.4 s | 9.4 s | 18.4 s |
| first `pkgs.hel` | 1.1 s | 1.1 s | 2.3 s |

For a language server, which runs for long, the JVM package is the better one; native-oracle
next.

## Compared with nixd

Compared with nixd (2.9.2, the same scripted sessions): the same completion of `pkgs`, `lib` and
NixOS options, hover and definitions of options and packages; nix-truffle also completes what
it evaluates in the file's own scope (`cfg.`, a local set's attributes), where nixd evaluates
fixed expressions only, and needs no configuration for a flake's machines; nixd also has
folding.

`tests/lsp.py` is a scripted session; see [development](development.md#tests).
