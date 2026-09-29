// A JS "module" for Nix's import: the value of the last expression is what Nix sees.
var module = ({
  fib: n => n < 2 ? n : module.fib(n - 1) + module.fib(n - 2),
  greet: name => `hello from JS, ${name}!`,
  // A Nix attrset is an interop object: members are forced lazily on access, lists are arrays.
  describe: pkg => `${pkg.pname}-${pkg.version} (${pkg.deps.length} deps)`,
  // Nix functions are executable; calling with 2 args applies them curried.
  callNix: (f, a, b) => f(a, b),
}); module
