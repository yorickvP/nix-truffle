let xs = [ 5 3 8 1 ]; in [
  (builtins.length xs) (builtins.head xs) (builtins.tail xs) (builtins.elemAt xs 2)
  (xs ++ [ 9 ]) (builtins.filter (x: x > 3) xs) (builtins.foldl' (a: b: a + b) 0 xs)
  (builtins.genList (i: i * i) 5) (builtins.elem 8 xs) (builtins.elem 7 xs)
  (builtins.all (x: x > 0) xs) (builtins.any (x: x > 7) xs)
  (builtins.concatLists [ [ 1 ] [ 2 3 ] [ ] ]) (builtins.concatMap (x: [ x x ]) [ 1 2 ])
  (builtins.sort builtins.lessThan xs) (builtins.sort (a: b: a > b) xs)
  (builtins.partition (x: x > 4) xs)
  (builtins.groupBy (x: if x > 4 then "big" else "small") xs)
  (builtins.length (builtins.genList (x: throw "never") 3))
]
