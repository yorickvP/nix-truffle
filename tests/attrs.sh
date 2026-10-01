#!/usr/bin/env bash
# Randomized checks of attribute sets: // against a plain merge, and (hashed) lookups against
# binary search. An optional argument is the random seed. NIX_TRUFFLE_CLASSPATH overrides
# target/classpath.txt.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cp="$root/target/classes:${NIX_TRUFFLE_CLASSPATH:-$(cat "$root/target/classpath.txt")}"
out="$root/target/test-classes-attrs"
mkdir -p "$out"
javac -d "$out" -cp "$cp" "$root/tests/attrs/AttrsCheck.java"
exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp "$out:$cp" AttrsCheck "$@" 2> >(grep -v '^WARNING' >&2)
