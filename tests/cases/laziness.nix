let
  ones = [ 1 ] ++ ones;
  nats = n: { head = n; tail = nats (n + 1); };
  take = n: s: if n == 0 then [ ] else [ s.head ] ++ take (n - 1) s.tail;
  unused = throw "never forced";
  fix = f: let x = f x; in x;
  pkgs = fix (self: { a = 1; b = self.a + 1; c = self.b * 10; });
  overlay = final: prev: { b = prev.b + 100; d = final.c; };
  extend = f: g: self: let super = f self; in super // g self super;
in [
  (take 5 (nats 0))
  (builtins.head [ 1 unused ])
  ({ a = unused; b = 2; }).b
  pkgs
  (fix (extend (self: { a = 1; b = self.a + 1; c = self.b * 10; }) overlay))
  (builtins.length [ unused unused ])
  (let x = builtins.trace "evaluated once" 5; in x + x)
  (builtins.seq [ unused ] "seq is shallow")
  (builtins.tryEval (throw "caught"))
  (builtins.tryEval (assert false; 1))
  (builtins.tryEval 42)
]
