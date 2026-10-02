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
          lib = pkgs.lib;
          system = pkgs.stdenv.hostPlatform.system;
          graalvm = pkgs.graalvmPackages.graalvm-ce;
          # Oracle GraalVM is under the GraalVM Free Terms and Conditions (unfree in nixpkgs): allowed
          # here for the native-oracle package only.
          graalvm-oracle = (import nixpkgs {
            inherit system;
            config.allowUnfreePredicate = p: lib.getName p == "graalvm-oracle";
          }).graalvmPackages.graalvm-oracle;

          # On the JVM (GraalVM's, for the Truffle compiler): bin/nix-truffle, which also runs the
          # daemon (NIX_TRUFFLE_DAEMON=1), with the jars in share/nix-truffle. `profile` picks
          # the Truffle runtime in pom.xml, to match `jdk`.
          jvm = { pname, jdk, profile, mvnHash }: pkgs.maven.buildMavenPackage {
            inherit pname version src mvnHash;
            mvnJdk = graalvm;
            mvnParameters = "-P${profile}";
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
                --set-default NIX_TRUFFLE_JAVA ${jdk}/bin/java \
                --prefix PATH : ${lib.makeBinPath [ pkgs.coreutils ]}
              runHook postInstall
            '';

            meta = {
              description = "A Nix evaluator on GraalVM's Truffle";
              mainProgram = "nix-truffle";
              platforms = lib.platforms.unix;
            };
          };

          # A native executable of a JVM package's jars (bin/build-native).
          native = { pname, jars, jdk, flags ? [ ], description }: pkgs.stdenv.mkDerivation {
            inherit pname version src;
            nativeBuildInputs = [ jdk ];
            buildPhase = ''
              runHook preBuild
              export HOME=$TMPDIR
              mkdir -p $out/bin
              NIX_TRUFFLE_CLASSPATH="${jars}/share/nix-truffle/nix-truffle.jar:$(cat ${jars}/share/nix-truffle/classpath)" \
                NIX_TRUFFLE_NATIVE_OUT=$out/bin/nix-truffle \
                bash bin/build-native -J-Xmx16g ${lib.escapeShellArgs flags}
              runHook postBuild
            '';
            dontInstall = true;
            meta = jars.meta // { inherit description; };
          };

          # The Maven dependencies and plugins (mvnHash): when pom.xml changes them, set it to
          # lib.fakeHash and take the hash from the error.
          nix-truffle = jvm {
            pname = "nix-truffle";
            jdk = graalvm;
            profile = "community";
            mvnHash = "sha256-rusip3Hz9nSsfXwKUKX/cXMNO0j96k097RreB4ARLjk=";
          };
        in {
          inherit nix-truffle;
          default = nix-truffle;
          # Starts in milliseconds, but GraalVM CE's native images only have the serial
          # collector, which makes large evaluations slow.
          native = native {
            pname = "nix-truffle-native";
            jars = nix-truffle;
            jdk = graalvm;
            description = "A Nix evaluator on GraalVM's Truffle (native executable)";
          };
        } // lib.optionalAttrs (pkgs.stdenv.hostPlatform.isLinux && lib.meta.availableOn pkgs.stdenv.hostPlatform graalvm-oracle) {
          # Oracle GraalVM's native image has G1 (on Linux), which collects big heaps in parallel.
          # Its defaults here: the heap may grow to three quarters of the memory but at most 30 GB
          # (as bin/nix-truffle's), and G1 may take up to half the time and promote survivors
          # after one collection (with its own defaults, a NixOS evaluation takes three times the
          # memory, and is 3% faster).
          native-oracle = native {
            pname = "nix-truffle-native-oracle";
            jars = jvm {
              pname = "nix-truffle-oracle";
              jdk = graalvm-oracle;
              profile = "oracle";
              mvnHash = "sha256-gUTzr2uYykYazlACUTy71tPC/R/PwvxS4PVTdF84c74=";
            };
            jdk = graalvm-oracle;
            flags = [ "--gc=G1" "-R:MaxRAMPercentage=75" "-R:ErgoHeapSizeLimit=${toString (30 * 1024 * 1024 * 1024)}"
              "-R:GCTimeRatio=1" "-R:MaxTenuringThreshold=1" ];
            description = "A Nix evaluator on GraalVM's Truffle (native executable, Oracle GraalVM with G1)";
          };
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
