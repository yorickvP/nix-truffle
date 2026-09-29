let s = { a = 1; b = 2; map = "shadowed?"; }; a = "lexical"; in [
  (with s; b)
  (with s; a)
  (with s; map)
  (with s; with { b = 3; }; b)
  (with { x = 1; }; with { y = 2; }; x + y)
  (with builtins; length [ 1 2 ])
  (with { true = 5; }; true)
]
