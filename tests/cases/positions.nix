# builtins.unsafeGetAttrPos: which position each kind of attribute gets, and which builtins keep
# them. nixos/lib/eval-config.nix uses it to decide how to wrap modules, which changes .drv paths.
let
  x = { p = 1; q = 2; };
  s = {
    a = 1;
      b.c.d = 2;
    inherit x;
    inherit (x) p q;
    ${"dyn"} = 3;
    "str" = 4;
    e = { f = 1; };
    e.g = 2;
  };
  r = rec { a = 1; b = a; };
  fa = builtins.functionArgs ({ y, z ? 1 }: y);
  pos = n: set:
    let p = builtins.unsafeGetAttrPos n set;
    in if p == null then null else "${baseNameOf p.file}:${toString p.line}:${toString p.column}";
in {
  literal = map (n: pos n s) [ "a" "b" "x" "p" "q" "dyn" "str" "e" ];
  nested = [ (pos "c" s.b) (pos "d" s.b.c) (pos "f" s.e) (pos "g" s.e) ];
  recursive = pos "b" r;
  update = [ (pos "a" (s // { z = 1; })) (pos "z" (s // { z = 1; })) (pos "a" (s // { a = 5; })) ];
  removeAttrs = pos "a" (removeAttrs s [ "b" ]);
  intersectAttrs = [ (pos "a" (builtins.intersectAttrs { a = 0; } s)) (pos "a" (builtins.intersectAttrs s { a = 0; })) ];
  listToAttrs = pos "k" (builtins.listToAttrs [ { name = "k";
    value = 1; } ]);
  mapAttrs = pos "a" (builtins.mapAttrs (n: v: v) s);
  functionArgs = [ (pos "y" fa) (pos "z" fa) ];
  zipAttrsWith = pos "a" (builtins.zipAttrsWith (n: v: v) [ s ]);
  fromJSON = pos "a" (builtins.fromJSON "{\"a\":1}");
  builtins = pos "map" builtins;
  imported = pos "version" (import ./_lib.nix);
  missing = builtins.unsafeGetAttrPos "nope" s;
  types = builtins.mapAttrs (n: v: builtins.typeOf v) (builtins.unsafeGetAttrPos "a" s);
}
