# The Pkl schema of NixOS's options, which host.pkl amends. Plain Nix (any Nix runs it), from
# this directory:
#
#   nix eval --raw -f schema.nix > nixos.pkl
#
# For a configuration of your own, generate it from that configuration (without its Pkl module):
# its own modules' options are in it too.
let
  nixpkgs = <nixpkgs>;
  nixos = import (nixpkgs + "/nixos") {
    configuration = { fileSystems."/".device = "none"; boot.loader.grub.enable = false; system.stateVersion = "25.11"; };
  };
in
(import ../../../nixos/pkl.nix { lib = import (nixpkgs + "/lib"); }).schema nixos.options
