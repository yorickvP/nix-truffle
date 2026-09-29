let x = "world"; n = 3; in [
  "hello ${x}"
  "a\nb\t\"c\" \${x} $${x} $$"
  ''
    indented
      more
    back ${x}
  ''
  ''one line''
  ''  keep '''quotes''' and ''${"dollar"} ''\n''
  "${toString n} items"
  ("a" + "b")
  (builtins.stringLength "hello")
  (builtins.substring 1 3 "abcdef")
  (builtins.substring 4 10 "abcdef")
  (builtins.concatStringsSep ", " [ "a" "b" "c" ])
  (builtins.replaceStrings [ "o" "l" ] [ "0" "1" ] "hello world")
  (builtins.replaceStrings [ "" ] [ "-" ] "abc")
  (toString [ 1 "a" [ true false null ] ])
  (toString 1.5)
  (builtins.toJSON { a = [ 1 "x" null true ]; b.c = "d"; })
]
