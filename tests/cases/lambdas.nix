let
  f = { a, b ? a * 2, ... }@args: [ a b (args ? c) ];
  g = x: y: x - y;
  h = { x ? 1 }: x;
  compose = f: g: x: f (g x);
in [
  (f { a = 3; c = 1; })
  (f { a = 3; b = 1; })
  (g 10 3)
  (h { })
  ((compose (x: x * 2) (x: x + 1)) 5)
  (builtins.functionArgs f)
  (builtins.functionArgs g)
  (let fn = { __functor = self: x: self.base + x; base = 10; }; in fn 5)
  ((x: x) (y: y) 7)
  (map (x: x * x) [ 1 2 3 ])
]
