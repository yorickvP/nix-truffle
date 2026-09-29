[
  (if 1 < 2 then "yes" else "no")
  (assert 1 == 1; "asserted")
  (builtins.genericClosure { startSet = [ { key = 1; } ]; operator = x: if x.key < 5 then [ { key = x.key + 1; } ] else [ ]; })
  (builtins.zipAttrsWith (n: vs: vs) [ { a = 1; } { a = 2; b = 3; } ])
  (builtins.bitAnd 12 10) (builtins.bitOr 12 10) (builtins.bitXor 12 10)
  (builtins.deepSeq [ 1 2 ] "deep")
  (let f = x: x; in [ f ] == [ f ])
  ({ a = 1; } // { })
  (builtins.isFunction builtins.map)
  (__length [ 1 2 3 ])
  (x: x)
]
