# Exercises nixpkgs' lib (needs NIX_PATH=nixpkgs=...), compared against real Nix in tests/run.sh.
let lib = import <nixpkgs/lib>; in with lib; {
  fix = fix (self: { x = 1; y = self.x + 1; });
  mapAttrsToList = mapAttrsToList (n: v: "${n}=${toString v}") { a = 1; b = 2; };
  splitString = splitString "," "a,b,c";
  foldr = foldr (a: b: a + b) 0 (range 1 100);
  majorMinor = versions.majorMinor "2.12.1";
  toUpper = toUpper "hello";
  recursiveUpdate = recursiveUpdate { a.b = 1; a.c = 2; } { a.b = 3; };
  extensible = removeAttrs ((makeExtensible (self: { a = 1; b = self.a + 1; })).extend (final: prev: { a = 10; })) [ "extend" "__unfix__" ];
  toJSON = generators.toJSON { } { a = [ 1 2 ]; };
  escapeShellArg = escapeShellArg "it's";
  unique = unique [ 1 2 1 3 ];
  filterAttrs = filterAttrs (n: v: v > 1) { a = 1; b = 2; };
  pipe = pipe 5 [ (x: x + 1) (x: x * 2) ];
  toPretty = generators.toPretty { } { a = 1; b = [ "x" ]; };
  toInt = toInt "42";
  optionals = optionals true [ 1 ] ++ optional false 2;
  zipLists = zipLists [ 1 2 ] [ "a" "b" ];
  hasPrefix = hasPrefix "foo" "foobar";
  cartesian = cartesianProduct { a = [ 1 2 ]; b = [ "x" ]; };
  modules = (evalModules {
    modules = [
      { options.foo = mkOption { type = types.int; default = 1; }; }
      { options.bar = mkOption { type = types.listOf types.str; default = [ ]; }; }
      { options.baz = mkOption { type = types.attrsOf types.int; default = { }; }; }
      ({ config, ... }: { foo = mkForce 5; bar = [ "a" ]; baz.x = config.foo * 2; })
      { bar = mkBefore [ "b" ]; baz.y = 1; }
    ];
  }).config;
}
