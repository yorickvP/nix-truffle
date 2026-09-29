package nixtruffle.launcher;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;

import nixtruffle.runtime.Bytes;

/** {@code nix-truffle flake lock [FLAKEREF]}: writes the flake's lock file, like {@code nix flake lock}. */
final class FlakeCommand {
    private FlakeCommand() {}

    static int run(Main.Options options, String[] args) {
        String command = null;
        String ref = ".";
        for (int i = 0; i < args.length; ) {
            int used = options.parse(args, i);
            if (used > 0) {
                i += used;
                continue;
            }
            if (command == null) command = args[i]; else ref = args[i];
            i++;
        }
        if (!"lock".equals(command)) {
            System.err.println("usage: nix-truffle flake lock [FLAKEREF]");
            return 1;
        }
        try (Context context = options.builder(false).build()) {
            context.eval("nix", "__nixTruffle").getMember("flakeLock").execute((Object) Bytes.get(Bytes.fromJava(ref)));
            return 0;
        } catch (PolyglotException e) {
            if (e.isHostException() || e.isInternalError()) throw e;
            Main.printErr(System.err, Main.describe(e));
            return 1;
        }
    }
}
