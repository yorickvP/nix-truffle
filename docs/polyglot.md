# Polyglot: other Truffle languages

Nix values and those of other Truffle languages (JavaScript is bundled) go both ways:

```
$ nix-truffle examples/polyglot.nix
{ callback = 42; described = "hello-2.12 (2 deps)"; fib = 6765; ... }
```

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
