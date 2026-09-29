[
  (rec { a = 1; b = a + 1; c = b * 10; })
  (rec { a = b; b = 2; })
  (let a = b; b = 1; in a)
  (let fib = n: if n < 2 then n else fib (n - 1) + fib (n - 2); in fib 15)
  (let x = { y = x.z; z = 5; }; in x.y)
  (let inherit (builtins) length; in length [ 1 2 3 ])
  (let a = 1; in rec { inherit a; b = a; })
  (let a = 1; s = { inherit a; b.c = a; inherit (s.b) c; }; in s)
  (rec { a.b = 1; c = a.b + 1; })
]
