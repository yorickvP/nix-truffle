# host.pkl, in Nix.
{ config, lib, pkgs, ... }: {
  networking.hostName = "pkl-demo";
  networking.firewall.allowedTCPPorts = [ 22 80 443 ];
  time.timeZone = "Europe/Amsterdam";
  users.users.alice = { isNormalUser = true; description = "Alice"; extraGroups = [ "wheel" "networkmanager" ]; };
  environment.systemPackages = [ pkgs.htop pkgs.git pkgs.python3Packages.requests ];
  environment.defaultPackages = [ ];
  environment.etc.motd.text = "Welcome to ${config.networking.hostName}, NixOS ${config.system.nixos.release}";
  services.openssh = { enable = true; settings = { PermitRootLogin = "no"; PasswordAuthentication = false; }; };
  services.nginx = { enable = true; virtualHosts."example.org".root = "/var/www"; };
  systemd.services.nginx.serviceConfig.ProtectSystem = lib.mkForce "full";
  fileSystems."/" = { device = "/dev/sda1"; fsType = "ext4"; };
  boot.loader.grub.device = "/dev/sda";
  system.stateVersion = "25.11";
}
