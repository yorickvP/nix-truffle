#!/usr/bin/env bash
# What holds the memory in a heap dump of nix-truffle (bench/heap/Owners.java): bytes of arrays,
# strings and thunks by the fields that reference them. Make a dump of a running evaluation with
#
#   jcmd PID GC.heap_dump /some/dir/heap.hprof
#
# (it holds the live heap, a few GB for a NixOS evaluation), then: bench/owners.sh heap.hprof
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/target/bench-classes"
mkdir -p "$out"
javac -d "$out" "$root/bench/heap/Owners.java"
exec java -Xmx12g -cp "$out" Owners "$1" 2> >(grep -v '^pass' >&2)
