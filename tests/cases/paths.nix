[ (baseNameOf "/a/b/c.nix") (dirOf "/a/b/c") (builtins.isPath ./foo) (toString (./foo + "/bar")) (baseNameOf ./x/y.nix) ]
