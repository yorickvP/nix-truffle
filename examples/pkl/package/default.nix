# Packages written in Pkl: each .pkl file amends derivation.pkl (stdenv.mkDerivation's
# arguments), and hello-quiet.pkl amends hello.pkl, as overrideAttrs would.
#   nix-truffle --instantiate examples/pkl/package -A hello-quiet
{ pkgs ? import <nixpkgs> { } }:
let
  inherit (pkgs) lib;
  # Without what isn't set: nulls and empty lists or sets.
  set = lib.filterAttrs (_: v: v != null && v != [ ] && v != { });
  package = file:
    let args = builtins.pkl { module = file; nix = { inherit pkgs; }; };
    in pkgs.stdenv.mkDerivation (set args // { meta = set args.meta; });
in
{
  hello = package ./hello.pkl;
  hello-quiet = package ./hello-quiet.pkl;
}
