# host.pkl and host.nix as NixOS systems: the same one. With nix-truffle (builtins.pkl), after
# schema.nix, from the repository root:
#
#   nix-truffle -I nixpkgs=... examples/pkl/nixos
let
  nixpkgs = <nixpkgs>;
  pkl = import ../../../nixos/pkl.nix { lib = import (nixpkgs + "/lib"); };
  system = configuration: (import (nixpkgs + "/nixos") { inherit configuration; }).config.system.build.toplevel.drvPath;
in
{
  fromPkl = system (pkl.module ./host.pkl);
  fromNix = system ./host.nix;
}
