package nixtruffle.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.Source;
import nixtruffle.NixContext;
import nixtruffle.fs.Fs;
import nixtruffle.runtime.Apply;
import nixtruffle.runtime.Bytes;
import nixtruffle.runtime.NixAttrs;
import nixtruffle.runtime.NixException;
import nixtruffle.runtime.NixFunction;
import nixtruffle.runtime.NixList;
import nixtruffle.runtime.NixNull;
import nixtruffle.runtime.NixPath;
import nixtruffle.runtime.NixString;
import nixtruffle.runtime.Values;
import org.graalvm.polyglot.io.ByteSequence;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static nixtruffle.runtime.Thunk.force;

/**
 * {@code builtins.wasm} from Determinate Nix ({@code libexpr/primops/wasm.cc}, the host interface is
 * {@code doc/manual/source/protocols/wasm.md} there), running modules with GraalWasm.
 *
 * <p>{@code builtins.wasm { path = ./m.wasm; function = "f"; } arg} instantiates the module and
 * calls {@code f} with the ID of {@code arg}; Nix values are {@code u32} IDs that the module works
 * with through the host functions in the {@code env} module. A module that imports from {@code
 * wasi_snapshot_preview1} runs its {@code _start} instead, gets the argument's ID as {@code
 * argv[1]}, and returns with {@code env.return_to_nix}. The WASI functions are implemented here,
 * like wasmtime's default configuration: no files, no environment, output becomes warnings.
 */
final class WasmBuiltin {
    private WasmBuiltin() {}

    private static final InteropLibrary INTEROP = InteropLibrary.getUncached();
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    private static NixException error(String message) {
        return NixException.error(message, null);
    }

    static void install() {
        Builtins.defFeature("wasm", 2, "wasm-builtin", a -> wasm(a[0], a[1]));
    }

    // ------------------------------------------------------------ modules

    /** A compiled module (a GraalWasm module object) and whether it uses WASI. */
    record Module(String name, Object module, boolean wasi) {}

    private static int moduleCounter;

    @TruffleBoundary
    private static Module compile(byte[] bytes, String name) {
        NixContext ctx = NixContext.get(null);
        // GraalWasm names modules after their source; make the names unique.
        Source source = Source.newBuilder("wasm", ByteSequence.create(bytes), "nix-wasm-" + (++moduleCounter) + "-" + Bytes.toJava(name)).build();
        Object module;
        try {
            module = ctx.env.parsePublic(source).call();
        } catch (AbstractTruffleException e) {
            if (e instanceof NixException) throw e;
            throw error("cannot load Wasm module '" + name + "': " + Bytes.fromJava(String.valueOf(e.getMessage())));
        }
        return new Module(name, module, importsWasi(bytes));
    }

    /** Whether the module imports anything from {@code wasi_snapshot_preview1} (read from its import section). */
    private static boolean importsWasi(byte[] b) {
        int[] pos = {8};
        try {
            while (pos[0] < b.length) {
                int id = b[pos[0]++] & 0xff;
                int size = (int) leb(b, pos);
                int end = pos[0] + size;
                if (id == 2) {
                    long count = leb(b, pos);
                    for (long i = 0; i < count; i++) {
                        String module = name(b, pos);
                        if (module.equals("wasi_snapshot_preview1")) return true;
                        name(b, pos);
                        int kind = b[pos[0]++] & 0xff;
                        switch (kind) {
                            case 0 -> leb(b, pos); // function: type index
                            case 1 -> { // table: reftype, limits
                                pos[0]++;
                                limits(b, pos);
                            }
                            case 2 -> limits(b, pos); // memory
                            case 3 -> pos[0] += 2; // global: valtype, mutability
                            case 4 -> { // tag: attribute, type index
                                pos[0]++;
                                leb(b, pos);
                            }
                            default -> {
                                return false;
                            }
                        }
                    }
                    return false;
                }
                pos[0] = end;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            // A malformed module; GraalWasm has rejected it already if so.
        }
        return false;
    }

    private static long leb(byte[] b, int[] pos) {
        long result = 0;
        int shift = 0;
        while (true) {
            int x = b[pos[0]++] & 0xff;
            result |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return result;
            shift += 7;
        }
    }

    private static String name(byte[] b, int[] pos) {
        int len = (int) leb(b, pos);
        String s = new String(b, pos[0], len, StandardCharsets.UTF_8);
        pos[0] += len;
        return s;
    }

    private static void limits(byte[] b, int[] pos) {
        int flags = b[pos[0]++] & 0xff;
        leb(b, pos);
        if ((flags & 1) != 0) leb(b, pos);
    }

    /** {@code wat2wasm} from wabt: GraalWasm only reads the binary format. */
    @TruffleBoundary
    private static byte[] wat2wasm(String wat) {
        String dir = nixtruffle.fetch.Fetcher.tempDir("wat");
        try {
            Fs.writeFile(dir + "/module.wat", Bytes.get(wat), 0600);
            Process p = nixtruffle.util.Proc.processBuilder(java.util.List.of("wat2wasm", Bytes.toJava(dir) + "/module.wat", "-o", Bytes.toJava(dir) + "/module.wasm"))
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) throw error("cannot compile WebAssembly text: " + Bytes.fromJava(out.strip()));
            return Fs.readFile(dir + "/module.wasm");
        } catch (IOException e) {
            throw error("compiling WebAssembly text (the 'wat' attribute) needs 'wat2wasm' from wabt on the PATH: " + Bytes.fromJava(String.valueOf(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error("interrupted while compiling WebAssembly text");
        } finally {
            nixtruffle.fetch.Fetcher.deleteTemp(dir);
        }
    }

    // ---------------------------------------------------------- the builtin

    @TruffleBoundary
    private static Object wasm(Object configArg, Object arg) {
        NixAttrs config = Values.attrs(configArg);
        for (String name : config.keys) {
            if (!name.equals("path") && !name.equals("wat") && !name.equals("function")) {
                throw error("unknown attribute '" + name + "' in first argument to `builtins.wasm`");
            }
        }
        Object pathAttr = config.getRaw("path");
        Object watAttr = config.getRaw("wat");
        if (pathAttr != null && watAttr != null) throw error("'path' and 'wat' are mutually exclusive in first argument to `builtins.wasm`");
        if (pathAttr == null && watAttr == null) throw error("missing required 'path' or 'wat' attribute in first argument to `builtins.wasm`");

        NixContext ctx = NixContext.get(null);
        Module module;
        if (pathAttr != null) {
            String path = FileBuiltins.realisePath(pathAttr, FileBuiltins.Symlinks.FULL);
            module = (Module) ctx.wasmModules.get("path:" + path);
            if (module == null) {
                try {
                    module = compile(Fs.readFile(path), path.substring(path.lastIndexOf('/') + 1));
                } catch (IOException e) {
                    throw error(e.getMessage());
                }
                ctx.wasmModules.put("path:" + path, module);
            }
        } else {
            // Like CppNix, WAT modules aren't cached.
            module = compile(wat2wasm(Values.stringNoCtx(watAttr)), Bytes.fromJava("<inline wat>"));
        }

        Object functionAttr = config.getRaw("function");
        String function;
        if (module.wasi) {
            if (functionAttr != null) throw error("'function' attribute is not allowed for WASI modules");
            function = "_start";
        } else {
            if (functionAttr == null) throw error("missing required 'function' attribute in first argument to `builtins.wasm` for non-WASI modules");
            function = Values.stringNoCtx(functionAttr);
        }

        Instance instance = new Instance(module);
        int argId = instance.addValue(arg);
        try {
            if (module.wasi) {
                instance.args = List.of("wasi", Integer.toString(argId));
                try {
                    instance.execute(instance.export("_start"));
                    if (instance.resultId == 0) {
                        throw error("Wasm function '" + function + "' from '" + module.name + "' finished without returning a value");
                    }
                } catch (ReturnToNix e) {
                    // The result is in resultId.
                } catch (ProcExit e) {
                    if (instance.resultId == 0) throw error("Exited with i32 exit status " + e.status);
                } finally {
                    instance.flushLog();
                }
                return force(instance.getValue(instance.resultId));
            }
            instance.runFunction("nix_wasm_init_v1");
            Object res = instance.runFunction(function, argId);
            if (!(res instanceof Number) || res instanceof Long || INTEROP.hasArrayElements(res)) {
                if (res instanceof Long) throw error("Wasm function '" + function + "' from '" + module.name + "' did not return an i32 value");
                throw error("Wasm function '" + function + "' from '" + module.name + "' did not return exactly one value");
            }
            return force(instance.getValue(((Number) res).intValue()));
        } catch (NixException e) {
            throw e;
        } catch (AbstractTruffleException e) {
            // A trap: unreachable, out of bounds, stack overflow, ...
            throw error(Bytes.fromJava(String.valueOf(e.getMessage())));
        }
    }

    /** Thrown by {@code return_to_nix}: stops the WASI module. */
    private static final class ReturnToNix extends AbstractTruffleException {
        ReturnToNix() {
            super("return_to_nix");
        }
    }

    /** Thrown by WASI's {@code proc_exit}. */
    private static final class ProcExit extends AbstractTruffleException {
        final int status;

        ProcExit(int status) {
            super("proc_exit");
            this.status = status;
        }
    }

    // ------------------------------------------------------------ instance

    /** An instance of a module, with the Nix values it has been given IDs for. */
    private static final class Instance {
        final Module module;
        final Object exports;
        final Object memory;
        final Object allocFn;
        final ArrayList<Object> values = new ArrayList<>();
        String functionName;
        int resultId;
        List<String> args = List.of();
        final StringBuilder log = new StringBuilder();

        Instance(Module module) {
            this.module = module;
            Object instance;
            try {
                instance = INTEROP.instantiate(module.module(), imports());
                exports = INTEROP.readMember(instance, "exports");
            } catch (InteropException e) {
                throw error("cannot instantiate Wasm module '" + module.name() + "': " + Bytes.fromJava(String.valueOf(e.getMessage())));
            } catch (AbstractTruffleException e) {
                if (e instanceof NixException) throw e;
                throw error("cannot instantiate Wasm module '" + module.name() + "': " + Bytes.fromJava(String.valueOf(e.getMessage())));
            }
            memory = export("memory");
            if (!INTEROP.hasBufferElements(memory)) throw error("export 'memory' of Wasm module '" + module.name() + "' does not have the right type");
            Object alloc = null;
            if (INTEROP.isMemberReadable(exports, "nix_wasm_alloc")) {
                alloc = readMember(exports, "nix_wasm_alloc");
                if (!INTEROP.isExecutable(alloc)) throw error("export 'nix_wasm_alloc' of Wasm module '" + module.name() + "' is not a function");
            }
            allocFn = alloc;
            // ID 0 means a missing attribute (get_attr).
            values.add(null);
        }

        Object export(String name) {
            if (!INTEROP.isMemberReadable(exports, name)) throw error("Wasm module '" + module.name() + "' does not export '" + name + "'");
            return readMember(exports, name);
        }

        Object runFunction(String name, Object... args) {
            functionName = name;
            Object fn = export(name);
            if (!INTEROP.isExecutable(fn)) throw error("export '" + name + "' of Wasm module '" + module.name() + "' does not have the right type");
            return execute(fn, args);
        }

        Object execute(Object fn, Object... args) {
            try {
                return INTEROP.execute(fn, args);
            } catch (InteropException e) {
                throw error("cannot call Wasm function of '" + module.name() + "': " + Bytes.fromJava(String.valueOf(e.getMessage())));
            }
        }

        int addValue(Object v) {
            values.add(v);
            return values.size() - 1;
        }

        Object getValue(long id) {
            if (id <= 0 || id >= values.size()) throw error("invalid ValueId " + id);
            return values.get((int) id);
        }

        private Object imports() {
            TreeMap<String, Object> env = new TreeMap<>(hostFunctions(this));
            if (module.wasi()) env.put("return_to_nix", fn(a -> {
                resultId = (int) u32(a[0]);
                throw new ReturnToNix();
            }));
            TreeMap<String, Object> modules = new TreeMap<>();
            modules.put("env", new ImportModule(env, false));
            if (module.wasi()) modules.put("wasi_snapshot_preview1", new ImportModule(Wasi.functions(this), true));
            return new ImportModule(modules, false);
        }

        // ---------------------------------------------------------- memory

        long memorySize() {
            try {
                return INTEROP.getBufferSize(memory);
            } catch (InteropException e) {
                throw error(e.getMessage());
            }
        }

        /** Checks that {@code len} bytes at {@code ptr} are in the guest's memory, like CppNix's {@code guestSpan}. */
        void check(long ptr, long len) {
            long size = memorySize();
            if (ptr > size || len > size - ptr) {
                throw error("Wasm memory access out of bounds (offset " + ptr + ", length " + len + ", memory size " + size + ")");
            }
        }

        byte[] read(long ptr, long len) {
            check(ptr, len);
            byte[] b = new byte[(int) len];
            try {
                INTEROP.readBuffer(memory, ptr, b, 0, (int) len);
            } catch (InteropException e) {
                throw error(e.getMessage());
            }
            return b;
        }

        String readString(long ptr, long len) {
            return Bytes.of(read(ptr, len));
        }

        void write(long ptr, byte[] data) {
            check(ptr, data.length);
            try {
                int i = 0;
                for (; i + 8 <= data.length; i += 8) {
                    long x = 0;
                    for (int j = 7; j >= 0; j--) x = x << 8 | (data[i + j] & 0xffL);
                    INTEROP.writeBufferLong(memory, LE, ptr + i, x);
                }
                for (; i < data.length; i++) INTEROP.writeBufferByte(memory, ptr + i, data[i]);
            } catch (InteropException e) {
                throw error(e.getMessage());
            }
        }

        int readU32(long ptr) {
            check(ptr, 4);
            try {
                return INTEROP.readBufferInt(memory, LE, ptr);
            } catch (InteropException e) {
                throw error(e.getMessage());
            }
        }

        void writeU32(long ptr, long v) {
            check(ptr, 4);
            try {
                INTEROP.writeBufferInt(memory, LE, ptr, (int) v);
            } catch (InteropException e) {
                throw error(e.getMessage());
            }
        }

        void writeU64(long ptr, long v) {
            check(ptr, 8);
            try {
                INTEROP.writeBufferLong(memory, LE, ptr, v);
            } catch (InteropException e) {
                throw error(e.getMessage());
            }
        }

        /** {@code count} value IDs (u32) at {@code ptr}. */
        long[] readIds(long ptr, long count) {
            check(ptr, count * 4);
            long[] ids = new long[(int) count];
            for (int i = 0; i < count; i++) ids[i] = Integer.toUnsignedLong(readU32(ptr + 4L * i));
            return ids;
        }

        /** Allocates in the guest with its {@code nix_wasm_alloc} export. */
        long allocInGuest(long size) {
            if (allocFn == null) throw error("Wasm module '" + module.name() + "' does not export 'nix_wasm_alloc'");
            Object res = execute(allocFn, (int) size);
            if (!(res instanceof Integer i)) throw error("'nix_wasm_alloc' of Wasm module '" + module.name() + "' did not return an i32");
            return Integer.toUnsignedLong(i);
        }

        // --------------------------------------------------------- logging

        void doWarn(String s) {
            NixContext ctx = NixContext.get(null);
            if (functionName != null) {
                ctx.printErr("warning: '" + module.name() + "' function '" + functionName + "': " + s);
            } else {
                ctx.printErr("warning: '" + module.name() + "': " + s);
            }
        }

        /** WASI output: one warning per line. */
        void log(String s) {
            log.append(s);
            int nl;
            while ((nl = log.indexOf("\n")) >= 0) {
                doWarn(log.substring(0, nl));
                log.delete(0, nl + 1);
            }
        }

        void flushLog() {
            if (!log.isEmpty()) doWarn(log.toString());
            log.setLength(0);
        }
    }

    // ------------------------------------------------------ host functions

    static long u32(Object o) {
        return Integer.toUnsignedLong(((Number) o).intValue());
    }

    private static int id(long id) {
        return (int) id;
    }

    /** The {@code env} host functions (see {@code protocols/wasm.md} in Determinate Nix). */
    private static Map<String, Object> hostFunctions(Instance in) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("panic", fn(a -> {
            throw error("Wasm panic: " + in.readString(u32(a[0]), u32(a[1])));
        }));
        m.put("warn", fn(a -> {
            in.doWarn(in.readString(u32(a[0]), u32(a[1])));
            return null;
        }));
        m.put("get_type", fn(a -> {
            Object v = force(in.getValue(u32(a[0])));
            if (v instanceof Long) return 1;
            if (v instanceof Double) return 2;
            if (v instanceof Boolean) return 3;
            if (NixString.is(v)) return 4;
            if (v instanceof NixPath) return 5;
            if (v instanceof NixNull) return 6;
            if (v instanceof NixAttrs) return 7;
            if (v instanceof NixList) return 8;
            if (v instanceof NixFunction) return 9;
            throw error("unsupported type");
        }));
        m.put("make_int", fn(a -> in.addValue(((Number) a[0]).longValue())));
        m.put("get_int", fn(a -> Values.integer(in.getValue(u32(a[0])))));
        m.put("make_float", fn(a -> in.addValue(((Number) a[0]).doubleValue())));
        m.put("get_float", fn(a -> {
            Object v = force(in.getValue(u32(a[0])));
            if (v instanceof Long l) return (double) l;
            if (v instanceof Double d) return d;
            throw NixException.typeError(v, "a float", null);
        }));
        m.put("make_string", fn(a -> in.addValue(in.readString(u32(a[0]), u32(a[1])))));
        m.put("copy_string", fn(a -> {
            String s = Values.string(in.getValue(u32(a[0])));
            long maxLen = u32(a[2]);
            if (s.length() <= maxLen) {
                in.check(u32(a[1]), maxLen);
                in.write(u32(a[1]), Bytes.get(s));
            }
            return s.length();
        }));
        m.put("make_path", fn(a -> {
            Object base = force(in.getValue(u32(a[0])));
            if (!(base instanceof NixPath p)) throw error("make_path expects a path value");
            String rel = in.readString(u32(a[1]), u32(a[2]));
            return in.addValue(new NixPath(NixPath.canonicalize(rel.startsWith("/") ? rel : p.path + "/" + rel)));
        }));
        m.put("copy_path", fn(a -> {
            Object v = force(in.getValue(u32(a[0])));
            if (!(v instanceof NixPath p)) throw error("copy_path expects a path value");
            long maxLen = u32(a[2]);
            if (p.path.length() <= maxLen) {
                in.check(u32(a[1]), maxLen);
                in.write(u32(a[1]), Bytes.get(p.path));
            }
            return p.path.length();
        }));
        m.put("make_bool", fn(a -> in.addValue(((Number) a[0]).intValue() != 0)));
        m.put("get_bool", fn(a -> Values.bool(in.getValue(u32(a[0]))) ? 1 : 0));
        m.put("make_null", fn(a -> in.addValue(NixNull.INSTANCE)));
        m.put("make_list", fn(a -> {
            long[] ids = in.readIds(u32(a[0]), u32(a[1]));
            Object[] items = new Object[ids.length];
            for (int i = 0; i < ids.length; i++) items[i] = in.getValue(ids[i]);
            return in.addValue(new NixList(items));
        }));
        m.put("copy_list", fn(a -> {
            Object[] items = Values.list(in.getValue(u32(a[0])));
            long ptr = u32(a[1]), maxLen = u32(a[2]);
            if (items.length <= maxLen) {
                in.check(ptr, 4L * items.length);
                for (int i = 0; i < items.length; i++) in.writeU32(ptr + 4L * i, in.addValue(items[i]));
            }
            return items.length;
        }));
        m.put("make_attrset", fn(a -> {
            long ptr = u32(a[0]), len = u32(a[1]);
            in.check(ptr, 12 * len);
            TreeMap<String, Object> attrs = new TreeMap<>();
            for (int i = 0; i < len; i++) {
                long at = ptr + 12L * i;
                String name = in.readString(Integer.toUnsignedLong(in.readU32(at)), Integer.toUnsignedLong(in.readU32(at + 4)));
                // A repeated name: the last one wins (as in CppNix's JSON output of such a set).
                attrs.put(name, in.getValue(Integer.toUnsignedLong(in.readU32(at + 8))));
            }
            return in.addValue(NixAttrs.fromMap(attrs));
        }));
        m.put("copy_attrset", fn(a -> {
            NixAttrs s = Values.attrs(in.getValue(u32(a[0])));
            long ptr = u32(a[1]), maxLen = u32(a[2]);
            if (s.size() <= maxLen) {
                in.check(ptr, 8 * maxLen);
                for (int i = 0; i < s.size(); i++) {
                    in.writeU32(ptr + 8L * i, in.addValue(s.values[i]));
                    in.writeU32(ptr + 8L * i + 4, s.keys[i].length());
                }
            }
            return s.size();
        }));
        m.put("copy_attrname", fn(a -> {
            NixAttrs s = Values.attrs(in.getValue(u32(a[0])));
            long idx = u32(a[1]), len = u32(a[3]);
            if (idx >= s.size()) throw error("copy_attrname: attribute index out of bounds");
            String name = s.keys[(int) idx];
            if (len != name.length()) throw error("copy_attrname: buffer length does not match attribute name length");
            in.write(u32(a[2]), Bytes.get(name));
            return null;
        }));
        m.put("get_attr", fn(a -> {
            String name = in.readString(u32(a[1]), u32(a[2]));
            NixAttrs s = Values.attrs(in.getValue(u32(a[0])));
            Object v = s.getRaw(name);
            return v == null ? 0 : in.addValue(v);
        }));
        m.put("call_function", fn(a -> {
            Object fun = force(in.getValue(u32(a[0])));
            if (!(fun instanceof NixFunction) && !(fun instanceof NixAttrs s && s.getRaw("__functor") != null)) {
                throw NixException.typeError(fun, "a function", null);
            }
            long[] ids = in.readIds(u32(a[1]), u32(a[2]));
            Object[] args = new Object[ids.length];
            for (int i = 0; i < ids.length; i++) args[i] = in.getValue(ids[i]);
            return in.addValue(Builtins.call(fun, args));
        }));
        m.put("make_app", fn(a -> {
            long funId = u32(a[0]);
            long[] ids = in.readIds(u32(a[1]), u32(a[2]));
            if (ids.length == 0) return id(funId);
            Object res = in.getValue(funId);
            for (long argId : ids) res = Apply.lazy(res, in.getValue(argId));
            return in.addValue(res);
        }));
        m.put("read_file", fn(a -> {
            byte[] contents = readFile(in.getValue(u32(a[0])));
            long ptr = u32(a[1]), len = u32(a[2]);
            if (contents.length <= len) {
                in.check(ptr, len);
                in.write(ptr, contents);
            }
            return contents.length;
        }));
        m.put("read_file_v2", fn(a -> {
            byte[] contents = readFile(in.getValue(u32(a[0])));
            long ptr = in.allocInGuest(contents.length);
            in.write(ptr, contents);
            return (long) contents.length << 32 | ptr;
        }));
        return m;
    }

    private static byte[] readFile(Object pathValue) {
        String path = FileBuiltins.realisePath(pathValue, FileBuiltins.Symlinks.FULL);
        try {
            return Fs.readFile(path);
        } catch (IOException e) {
            throw error(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- WASI

    /**
     * {@code wasi_snapshot_preview1} as wasmtime's default {@code WasiConfig} has it: the arguments,
     * no environment, empty stdin, stdout and stderr as warnings, no preopened directories (so no
     * files), and the real clock and randomness. Everything else fails with an errno.
     */
    private static final class Wasi {
        static final int ESUCCESS = 0, EBADF = 8, EINVAL = 28, ENOSYS = 52, ESPIPE = 70;
        private static final SecureRandom RANDOM = new SecureRandom();

        static Map<String, Object> functions(Instance in) {
            TreeMap<String, Object> m = new TreeMap<>();
            m.put("args_sizes_get", fn(a -> {
                long size = 0;
                for (String s : in.args) size += s.getBytes(StandardCharsets.UTF_8).length + 1;
                in.writeU32(u32(a[0]), in.args.size());
                in.writeU32(u32(a[1]), size);
                return ESUCCESS;
            }));
            m.put("args_get", fn(a -> {
                long argv = u32(a[0]), buf = u32(a[1]);
                for (int i = 0; i < in.args.size(); i++) {
                    byte[] s = (in.args.get(i) + "\0").getBytes(StandardCharsets.UTF_8);
                    in.writeU32(argv + 4L * i, buf);
                    in.write(buf, s);
                    buf += s.length;
                }
                return ESUCCESS;
            }));
            m.put("environ_sizes_get", fn(a -> {
                in.writeU32(u32(a[0]), 0);
                in.writeU32(u32(a[1]), 0);
                return ESUCCESS;
            }));
            m.put("environ_get", fn(a -> ESUCCESS));
            m.put("clock_res_get", fn(a -> {
                if (((Number) a[0]).intValue() > 3) return EINVAL;
                in.writeU64(u32(a[1]), 1);
                return ESUCCESS;
            }));
            m.put("clock_time_get", fn(a -> {
                long time = switch (((Number) a[0]).intValue()) {
                    case 0 -> {
                        java.time.Instant now = java.time.Instant.now();
                        yield now.getEpochSecond() * 1_000_000_000L + now.getNano();
                    }
                    case 1, 2, 3 -> System.nanoTime();
                    default -> -1;
                };
                if (time == -1) return EINVAL;
                in.writeU64(u32(a[2]), time);
                return ESUCCESS;
            }));
            m.put("fd_write", fn(a -> {
                int fd = ((Number) a[0]).intValue();
                if (fd != 1 && fd != 2) return EBADF;
                long iovs = u32(a[1]), count = u32(a[2]), total = 0;
                StringBuilder sb = new StringBuilder();
                for (long i = 0; i < count; i++) {
                    long buf = Integer.toUnsignedLong(in.readU32(iovs + 8 * i));
                    long len = Integer.toUnsignedLong(in.readU32(iovs + 8 * i + 4));
                    sb.append(in.readString(buf, len));
                    total += len;
                }
                in.log(sb.toString());
                in.writeU32(u32(a[3]), total);
                return ESUCCESS;
            }));
            m.put("fd_read", fn(a -> {
                if (((Number) a[0]).intValue() != 0) return EBADF;
                in.writeU32(u32(a[3]), 0);
                return ESUCCESS;
            }));
            m.put("fd_close", fn(a -> ((Number) a[0]).intValue() <= 2 ? ESUCCESS : EBADF));
            m.put("fd_fdstat_get", fn(a -> {
                if (((Number) a[0]).intValue() > 2) return EBADF;
                long p = u32(a[1]);
                in.write(p, new byte[24]);
                // A character device, with all rights.
                in.write(p, new byte[] {2});
                in.writeU64(p + 8, -1L);
                return ESUCCESS;
            }));
            m.put("fd_fdstat_set_flags", fn(a -> ((Number) a[0]).intValue() <= 2 ? ESUCCESS : EBADF));
            m.put("fd_seek", fn(a -> ((Number) a[0]).intValue() <= 2 ? ESPIPE : EBADF));
            m.put("fd_tell", fn(a -> ((Number) a[0]).intValue() <= 2 ? ESPIPE : EBADF));
            m.put("fd_prestat_get", fn(a -> EBADF));
            m.put("fd_prestat_dir_name", fn(a -> EBADF));
            m.put("proc_exit", fn(a -> {
                throw new ProcExit(((Number) a[0]).intValue());
            }));
            m.put("sched_yield", fn(a -> ESUCCESS));
            m.put("random_get", fn(a -> {
                byte[] b = new byte[(int) u32(a[1])];
                RANDOM.nextBytes(b);
                in.write(u32(a[0]), b);
                return ESUCCESS;
            }));
            return m;
        }

        /** Any other WASI function: file and socket functions have no descriptors to work on. */
        static Object unsupported(String name) {
            int errno = name.startsWith("fd_") || name.startsWith("path_") || name.startsWith("sock_") ? EBADF : ENOSYS;
            return fn(a -> errno);
        }
    }

    // ------------------------------------------------------------- interop

    interface Impl {
        Object call(Object[] args);
    }

    private static HostFunction fn(Impl impl) {
        return new HostFunction(impl);
    }

    /** A host function that Wasm imports. */
    @ExportLibrary(InteropLibrary.class)
    static final class HostFunction implements TruffleObject {
        final Impl impl;

        HostFunction(Impl impl) {
            this.impl = impl;
        }

        @ExportMessage
        boolean isExecutable() {
            return true;
        }

        @ExportMessage
        @TruffleBoundary
        Object execute(Object[] args) {
            return impl.call(args);
        }
    }

    /**
     * An import object: modules by name, or a module's functions. The WASI module has every
     * function (unknown ones fail with an errno), others only the ones they define.
     */
    @ExportLibrary(InteropLibrary.class)
    static final class ImportModule implements TruffleObject {
        final Map<String, Object> members;
        final boolean wasi;

        ImportModule(Map<String, Object> members, boolean wasi) {
            this.members = members;
            this.wasi = wasi;
        }

        @ExportMessage
        boolean hasMembers() {
            return true;
        }

        @ExportMessage
        @TruffleBoundary
        Object getMembers(boolean includeInternal) {
            return new Keys(members.keySet().toArray(new String[0]));
        }

        @ExportMessage
        @TruffleBoundary
        boolean isMemberReadable(String member) {
            return wasi || members.containsKey(member);
        }

        @ExportMessage
        @TruffleBoundary
        Object readMember(String member) throws UnknownIdentifierException {
            Object v = members.get(member);
            if (v == null && wasi) return Wasi.unsupported(member);
            if (v == null) throw UnknownIdentifierException.create(member);
            return v;
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class Keys implements TruffleObject {
        final String[] keys;

        Keys(String[] keys) {
            this.keys = keys;
        }

        @ExportMessage
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return keys.length;
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < keys.length;
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (index < 0 || index >= keys.length) throw InvalidArrayIndexException.create(index);
            return keys[(int) index];
        }
    }

    private static Object readMember(Object o, String name) {
        try {
            return INTEROP.readMember(o, name);
        } catch (InteropException e) {
            throw error(e.getMessage());
        }
    }
}
