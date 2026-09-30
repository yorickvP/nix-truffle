{ pkgs ? "default", n ? 1 }: {
  inherit pkgs;
  twice = n * 2;
  list = [ { a = "first"; } { a = "second"; } ];
  fn = { x ? 1 }: { got = x; };
  s = "str";
}
