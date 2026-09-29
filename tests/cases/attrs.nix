let s = { a = 1; b = { c = 2; }; "d e" = 3; }; in [
  s.a s.b.c s."d e" (s.x or 42) (s.b.x or "def") (s ? a) (s ? b.c) (s ? b.x) (s ? x.y)
  { a.b = 1; a.c = 2; }
  { a = { b = 1; }; a.c = 2; }
  (s // { a = 10; z = 26; })
  (builtins.attrNames { b = 1; a = 2; "A" = 3; })
  (builtins.attrValues { b = 1; a = 2; })
  (let k = "dyn"; in { ${k} = 1; "${k}2" = 2; ${null} = 3; })
  (builtins.hasAttr "a" s)
  (builtins.getAttr "a" s)
  (builtins.removeAttrs s [ "a" "b" ])
  (builtins.intersectAttrs { a = 0; x = 0; } s)
  (builtins.listToAttrs [ { name = "x"; value = 1; } { name = "y"; value = 2; } { name = "x"; value = 3; } ])
  (builtins.mapAttrs (n: v: "${n}=${toString v}") { a = 1; b = 2; })
  (builtins.catAttrs "a" [ { a = 1; } { b = 2; } { a = 3; } ])
  ({ a = throw "lazy"; } ? a)
]
