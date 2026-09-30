{
  description = "Tiny Nix interpreter on Truffle/GraalVM";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});
    in {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.graalvmPackages.graalvm-ce pkgs.maven pkgs.wabt ];
          JAVA_HOME = pkgs.graalvmPackages.graalvm-ce;
        };
      });
    };
}
