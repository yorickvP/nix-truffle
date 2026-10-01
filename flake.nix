{
  description = "A Nix evaluator on GraalVM's Truffle";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});
      version = builtins.head (builtins.match ".*<artifactId>nix-truffle</artifactId>[[:space:]]*<version>([^<]*)</version>.*"
        (builtins.readFile ./pom.xml));
      src = nixpkgs.lib.fileset.toSource {
        root = ./.;
        fileset = nixpkgs.lib.fileset.unions [ ./pom.xml ./src ./bin ];
      };
    in {
      packages = forAllSystems (pkgs:
        let
          graalvm = pkgs.graalvmPackages.graalvm-ce;

          # On the JVM (GraalVM's, for the Truffle compiler): bin/nix-truffle, which also runs the
          # daemon (NIX_TRUFFLE_DAEMON=1), with the jars in share/nix-truffle.
          nix-truffle = pkgs.maven.buildMavenPackage {
            pname = "nix-truffle";
            inherit version src;
            mvnJdk = graalvm;
            # The Maven dependencies and plugins: when pom.xml changes them, set this to
            # lib.fakeHash and take the hash from the error.
            mvnHash = "sha256-RUvksHiSRcMzlQj7fdFMNHOdzU63MeAtVdM1T7TC04c=";
            doCheck = false;
            nativeBuildInputs = [ pkgs.makeWrapper ];

            installPhase = ''
              runHook preInstall
              share=$out/share/nix-truffle
              mkdir -p $share/lib $out/bin
              cp target/nix-truffle.jar $share/
              # The dependencies, in classpath order (numbered, as some jars have the same name).
              i=0
              for jar in $(tr ':' '\n' < target/classpath.txt | grep '\.jar$'); do
                i=$((i + 1))
                cp "$jar" "$share/lib/$i-$(basename "$jar")"
                echo "$share/lib/$i-$(basename "$jar")"
              done | paste -sd: > $share/classpath
              cp bin/nix-truffle $out/bin/
              wrapProgram $out/bin/nix-truffle \
                --set-default NIX_TRUFFLE_JAVA ${graalvm}/bin/java \
                --prefix PATH : ${pkgs.lib.makeBinPath [ pkgs.coreutils ]}
              runHook postInstall
            '';

            meta = {
              description = "A Nix evaluator on GraalVM's Truffle";
              mainProgram = "nix-truffle";
              platforms = pkgs.lib.platforms.unix;
            };
          };

          # A native executable (bin/build-native): starts in milliseconds, but GraalVM CE's native
          # images only have the serial collector, which makes large evaluations slow.
          native = pkgs.stdenv.mkDerivation {
            pname = "nix-truffle-native";
            inherit version src;
            nativeBuildInputs = [ graalvm ];
            buildPhase = ''
              runHook preBuild
              export HOME=$TMPDIR
              mkdir -p $out/bin
              NIX_TRUFFLE_CLASSPATH="${nix-truffle}/share/nix-truffle/nix-truffle.jar:$(cat ${nix-truffle}/share/nix-truffle/classpath)" \
                NIX_TRUFFLE_NATIVE_OUT=$out/bin/nix-truffle \
                bash bin/build-native -J-Xmx16g
              runHook postBuild
            '';
            dontInstall = true;
            meta = nix-truffle.meta // { description = "A Nix evaluator on GraalVM's Truffle (native executable)"; };
          };
        in {
          inherit nix-truffle native;
          default = nix-truffle;
        });

      checks = forAllSystems (pkgs:
        let nix-truffle = self.packages.${pkgs.stdenv.hostPlatform.system}.default;
        in {
          default = pkgs.runCommand "nix-truffle-check" { nativeBuildInputs = [ nix-truffle ]; } ''
            export HOME=$TMPDIR
            [[ "$(nix-truffle -E '1 + 1')" == 2 ]]
            [[ "$(nix-truffle eval --extra-experimental-features nix-command --expr 'builtins.concatLists [ [ 1 ] [ 2 ] ]')" == "[ 1 2 ]" ]]
            nix-truffle --version | grep -q '^nix-truffle ${version}$'
            nix-truffle > /dev/null
            touch $out
          '';
        });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [ pkgs.graalvmPackages.graalvm-ce pkgs.maven pkgs.wabt ];
          JAVA_HOME = pkgs.graalvmPackages.graalvm-ce;
        };
      });
    };
}
