let d = derivation { name = "d"; builder = "/bin/sh"; system = "x86_64-linux"; }; in
builtins.toXML {
  a = 1; b = true; c = "str \"q\" <&>\nline2"; d = null; e = 1.5; f = ./xml.nix;
  g = [ 1 [ "x" ] { } ]; h = x: x; i = { a, b ? 1, ... }@args: a; j = builtins.map;
  drv = d; drv2 = [ d d ];
}
