# nix-truffle -I nixpkgs=... examples/pkl/example.nix
let
  pkgs = import <nixpkgs> { };

  # Nix data checked against Pkl types, completed with their defaults.
  services = builtins.pkl {
    module = ./services.pkl;
    amend.services = [
      { name = "web"; port = 443; environment.MODE = "production"; }
      { name = "api"; }
    ];
  };

  # A configuration file, typed in Pkl and rendered as YAML.
  prometheus = pkgs.writeText "prometheus.yml" (builtins.pkl {
    module = ./prometheus.pkl;
    output = true;
    amend.scrape_configs = map (s: {
      job_name = s.name;
      static_configs = [ { targets = [ "${s.name}.internal:${toString s.port}" ]; } ];
    }) services.services;
  });

  # Pkl using nixpkgs.
  tools = (builtins.pkl { module = ./tools.pkl; nix = { inherit pkgs; }; }).tools;
in
{
  inherit (services) urls;
  prometheus = prometheus.drvPath;
  inherit tools;
  # The store paths from Pkl keep their dependencies.
  script = (pkgs.writeShellScript "greet" (builtins.concatStringsSep "\n" (map (t: t.bin) tools))).drvPath;
}
