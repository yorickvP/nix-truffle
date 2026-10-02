# `builtins.wasm`

Determinate Nix's `builtins.wasm` (behind the `wasm-builtin` experimental feature) runs
WebAssembly modules; here they run on [GraalWasm](https://www.graalvm.org/webassembly/)
(`builtins/WasmBuiltin.java`, a port of Determinate Nix's `libexpr/primops/wasm.cc`):

```nix
builtins.wasm { path = ./fib.wasm; function = "fib"; } 40          # a plain module
builtins.wasm { path = ./plugin.wasm; } { some = "argument"; }     # a WASI module
builtins.wasm { wat = builtins.readFile ./fib.wat; function = "fib"; } 40
```

Nix values are `u32` IDs that the module manipulates through the host functions of the `env`
module (`get_type`, `make_int`, `copy_string`, `make_attrset`, `call_function`, `make_app`,
`read_file`, ...), with the same bounds checks. Every call gets a fresh instance of the
compiled module. A module that imports from `wasi_snapshot_preview1` runs its `_start` with
the argument's ID as `argv[1]` and returns with `return_to_nix`; nix-truffle implements the WASI
functions itself, like wasmtime's default configuration: no environment, empty stdin, no
preopened directories, and stdout/stderr become warnings, one per line. `wat` sources are
compiled with `wat2wasm` from wabt (in the dev shell), since GraalWasm reads only the binary
format.

All the examples of [nix-wasm-rust](https://github.com/DeterminateSystems/nix-wasm-rust) pass
their tests (YAML, INI, grep, Mandelbrot, and QuickJS running JavaScript from Nix through
WASI), and so do Determinate Nix's own `wasm.sh` cases (`tests/wasm.sh`). Compared with
Determinate Nix 3.22 on edge cases, two things differ on purpose: `make_attrset` with a repeated
name keeps the last one (Determinate Nix makes a set with the name twice), and `copy_attrset`
lists attributes by name (Determinate Nix in interning order, with a FIXME to sort them).
Instantiating a module costs more with GraalWasm (tens of microseconds for a typical Rust
module, which allocates a megabyte of memory) than with wasmtime's pooling allocator, which
shows in code that calls a Wasm function very often.
