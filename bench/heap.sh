#!/usr/bin/env bash
# The live heap after evaluating EXPR (impurely, with flakes), with the context still open, so
# what it keeps is alive (imported files and flakes, and the values they reference), and the N
# (default 30) classes that take the most of it:
#
#   bench/heap.sh '(builtins.getFlake "git+file:///path/to/flake").nixosConfigurations.HOST.config.system.build.toplevel.drvPath'
#
# NIX_TRUFFLE_CLASSPATH overrides target/classpath.txt.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cp="$root/target/classes:${NIX_TRUFFLE_CLASSPATH:-$(cat "$root/target/classpath.txt")}"
out="$root/target/bench-classes"
mkdir -p "$out"
javac -d "$out" -cp "$cp" "$root/bench/heap/Heap.java"
exec java -XX:+UseCompactObjectHeaders --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp "$out:$cp" Heap "$1" "${2:-30}" 2> >(grep -v '^WARNING' >&2)
