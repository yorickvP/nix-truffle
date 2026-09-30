{
  inputs.dep.url = "path:../dep";
  outputs = { self, dep }: let sys = "x86_64-linux"; in {
    packages.${sys} = {
      default = builtins.derivation { name = "def"; builder = "/bin/sh"; system = sys; };
      hello = builtins.derivation { name = "hello"; builder = "/bin/sh"; system = sys; args = [ "-c" "echo" ]; };
      both = "from-packages";
    };
    legacyPackages.${sys} = { both = "from-legacy"; legacy = { x = 1; y = [ 1 2 ]; }; };
    both = "top";
    lib = { f = x: x + 1; str = "a\nb"; n = 3; fl = 2.5; paths = ./sub; nested.deep."a.b" = true; };
    fromDep = dep.value;
    depSrc = builtins.readFile (dep + "/data");
    self' = builtins.attrNames self;
    sourceKeys = builtins.attrNames self.sourceInfo;
    list = [ 1 "two" { three = 3; } ];
    err = { ok = 1; bad = throw "nope"; };
    impure = builtins.currentSystem;
    file = builtins.readFile ./sub/data;
    outside = builtins.readFile /etc/hostname;
  };
}
