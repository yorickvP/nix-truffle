// Embedding the Nix language from a Java host through the GraalVM polyglot API.
// Run (after `mvn package`):
//   java -cp "target/classes:$(cat target/classpath.txt)" examples/Embed.java
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class Embed {
    public static void main(String[] args) {
        try (Context context = Context.newBuilder().allowAllAccess(true).build()) {
            Value pkgs = context.eval("nix", """
                let fix = f: let x = f x; in x;
                in fix (self: {
                  hello = { pname = "hello"; version = "2.12"; };
                  name = "${self.hello.pname}-${self.hello.version}";
                  broken = throw "only forced if someone reads it";
                })
                """);
            // Attrsets are interop objects; members are forced on demand.
            System.out.println("keys:   " + pkgs.getMemberKeys());
            System.out.println("name:   " + pkgs.getMember("name").asString());
            System.out.println("hello:  " + pkgs.getMember("hello"));

            // Nix functions are executable; extra arguments are applied curried.
            Value add = context.eval("nix", "a: b: a + b");
            System.out.println("add:    " + add.execute(40, 2).asLong());

            // Host values flow into Nix: here a Java lambda is called from Nix.
            Value apply = context.eval("nix", "f: map f [ 1 2 3 ]");
            Value doubled = apply.execute((java.util.function.Function<Long, Long>) x -> x * 2);
            System.out.println("mapped: " + doubled);

            try {
                pkgs.getMember("broken");
            } catch (org.graalvm.polyglot.PolyglotException e) {
                System.out.println("broken: " + e.getMessage());
            }
        }
    }
}
