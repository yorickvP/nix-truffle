# Laziness-heavy: lazy infinite streams, a fixpoint over an attrset, sharing.
let
  inherit (builtins) foldl' genList listToAttrs elemAt;
  # an infinite lazy stream of primes (trial division against earlier primes)
  nats = n: { head = n; tail = nats (n + 1); };
  filterS = p: s: if p s.head then { head = s.head; tail = filterS p s.tail; } else filterS p s.tail;
  take = n: s: if n == 0 then [ ] else [ s.head ] ++ take (n - 1) s.tail;
  primes = let go = s: { head = s.head; tail = go (filterS (x: x / s.head * s.head != x) s.tail); }; in go (nats 2);
  # a nixpkgs-style fixpoint: every package refers to the previous one through `self`
  fix = f: let x = f x; in x;
  size = 3000;
  pkgs = fix (self: listToAttrs (genList (i: {
    name = "p${toString i}";
    value = { version = if i == 0 then 0 else self."p${toString (i - 1)}".version + 1; };
  }) size));
in {
  primes = foldl' (a: b: a + b) 0 (take 1500 primes);
  last = pkgs."p${toString (size - 1)}".version;
}
