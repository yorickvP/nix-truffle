# Pkl

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
  A derivation's attributes are hidden properties (`pkgs.hello.version` reads one), and a `nix:`
  module that comes back to Nix is the Nix value itself, so `buildInputs { pkgs.openssl }` is a
  list of derivations. Store paths that come back keep their string context, also within other
  strings (`"\(pkgs.hello.outPath)/bin/hello"`), so derivations that use them depend on the
  right derivations.
- **Pkl calling Nix functions**: a Nix function's (or functor's) module has `call`.
  `pkgs.fetchurl.call(new { url = "..."; hash = "..." })` is a `NixCall` that is the call's result
  when it goes back to Nix: Nix evaluates it then, lazily, and it is the derivation itself. A
  call's `text` is its result now, as text (as `read` has values), for Pkl to use:
  `"\(lib.getExe.call(pkgs.hello).text) --greeting hi"`. Curried functions take
  `f.call(a).call(b)`, or `f.call2(a, b)` (up to `call5`: Pkl has neither variadic functions nor
  overloading). (Pkl can't get other values from outside at run time: `read` is text,
  imports are constant, so `text` sends the call as JSON in the URI of a `read`.)

`builtins.pkl` takes `module` (a path) or `text`, and optionally `amend` (with `module`),
`expression` (evaluated in the module instead of the whole module), `output`, `defaults`, and
`nix`.

With `defaults = false`, the value has only what the module's files set, not what its schema (the
module at the root of its amends chain) defines: a property set to null or to an empty listing is
there, one that isn't set isn't, and a listing has the elements the files added. It is lazy: its
attribute names are the properties' (Pkl knows them without evaluating anything), and each value
is evaluated (and type-checked) by Pkl when Nix uses it. So Pkl can read, through `nix:`, Nix
values that depend on the result, as long as a value doesn't depend on itself (that's "infinite
recursion"). This reads pkl-core's runtime objects, beyond its Java API.

Values:

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
  `hidden`).
- `amend` assigns every attribute (`name = ...`), replacing the module's default rather than
  amending it.
- Native images leave Pkl out: its language needs a native-image configuration of its own.
  `builtins.pkl` and `.pkl` imports say so there.

## Packages in Pkl

A package can be a Pkl module that amends a schema of `stdenv.mkDerivation`'s arguments, and a
variant a module that amends that one, as `overrideAttrs` would (`examples/pkl/package`):

```pkl
amends "derivation.pkl"
import "nix:pkgs" as pkgs

pname = "hello"
version = "2.12.3"
src = pkgs.fetchurl.call(new Dynamic {
  url = "mirror://gnu/hello/hello-\(version).tar.gz"
  hash = "sha256-DV9gFUOC/uELEUocNOeF2LH0kgc64tOm97FHaHs2aqA="
})
doCheck = true
meta { mainProgram = "hello"; license = pkgs.lib.licenses.gpl3Plus }
```

```pkl
amends "hello.pkl"
pname = "hello-quiet"
doCheck = false
configureFlags { "--disable-nls" }
```

```nix
pkgs.stdenv.mkDerivation (builtins.pkl { module = ./hello-quiet.pkl; nix = { inherit pkgs; }; })
```

Pkl checks the arguments (`nativeBuildInputs { "perl" }`: "Expected value of type `Module`, but
got type `String`"; a homepage that isn't `https:`), and the derivation is the one the same
arguments in Nix make. `derivation.pkl` is written by hand: mkDerivation has no option types to
generate it from.

## NixOS configurations in Pkl

The `pkl-nixos` branch writes NixOS configurations in Pkl: a schema generated from a
configuration's options, and a NixOS module made of a Pkl file that amends it (with `mkForce` and
friends, `nix:pkgs` and `nix:config`). It works (the same systems as the same definitions in Nix),
but it isn't a good fit: the module system merges many modules' definitions, with priorities and
conditions on the configuration, where Pkl has single-inheritance amending, so it takes
workarounds. Typed data in Nix configurations (an option's settings, rendered config files) is
the better fit, through `builtins.pkl`.
