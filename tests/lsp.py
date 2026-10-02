#!/usr/bin/env python3
"""nix-truffle lsp: a scripted session over stdio, checking each answer.

TRUFFLE overrides bin/nix-truffle. Needs only Python 3.
"""
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

root = Path(__file__).resolve().parent.parent
truffle = os.environ.get("TRUFFLE", str(root / "bin" / "nix-truffle"))


class Client:
    def __init__(self, init=None):
        self.init = init
        env = dict(os.environ, NIX_TRUFFLE_DAEMON="0")
        self.proc = subprocess.Popen([truffle, "lsp"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env)
        self.next_id = 0
        self.diagnostics = {}

    def send(self, msg):
        body = json.dumps(dict(msg, jsonrpc="2.0")).encode()
        self.proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        self.proc.stdin.flush()

    def read(self):
        length = None
        while True:
            line = self.proc.stdout.readline()
            if not line:
                raise EOFError("the server exited")
            line = line.strip()
            if not line:
                break
            name, _, value = line.partition(b":")
            if name.lower() == b"content-length":
                length = int(value)
        return json.loads(self.proc.stdout.read(length))

    def request(self, method, params):
        self.next_id += 1
        self.send({"id": self.next_id, "method": method, "params": params})
        while True:
            msg = self.read()
            if msg.get("method") == "textDocument/publishDiagnostics":
                self.diagnostics[msg["params"]["uri"]] = msg["params"]["diagnostics"]
            elif msg.get("id") == self.next_id:
                if "error" in msg:
                    raise RuntimeError(msg["error"])
                return msg["result"]

    def notify(self, method, params):
        self.send({"method": method, "params": params})

    def open(self, uri, text):
        self.notify("textDocument/didOpen", {"textDocument": {"uri": uri, "languageId": "nix", "version": 1, "text": text}})
        # The diagnostics come before the answer to any later request.
        self.request("textDocument/hover", {"textDocument": {"uri": uri}, "position": {"line": 0, "character": 0}})
        return self.diagnostics.get(uri, [])

    def at(self, method, uri, text, marker, delta=0):
        """A request at the position of `marker` in `text` (plus `delta` characters)."""
        offset = text.index(marker) + delta
        line = text.count("\n", 0, offset)
        character = offset - (text.rfind("\n", 0, offset) + 1)
        return self.request(method, {"textDocument": {"uri": uri}, "position": {"line": line, "character": character},
                                     "context": {"includeDeclaration": True}})


passed = failed = 0


def check(name, got, want):
    global passed, failed
    if got == want:
        passed += 1
    else:
        failed += 1
        print(f"FAIL {name}\n  want: {want!r}\n  got:  {got!r}")


tmp = Path(tempfile.mkdtemp())
(tmp / "other.nix").write_text("1\n")
(tmp / "dir").mkdir()
(tmp / "dir" / "default.nix").write_text("2\n")
main = (tmp / "main.nix").as_uri()
text = """{ pkgs, unusedArg, ... }:
let
  foo = 1 + missing;
  unused = 2;
  inherit (pkgs) lib;
in
with lib; {
  a = foo + alsoFromWith;
  b = map (x: x) [ fromWith ];
  c = import ./other.nix;
  d = import ./dir;
  e = builtins.length [ ];
}
"""

c = Client()
caps = c.request("initialize", {"processId": None, "rootUri": tmp.as_uri(), "capabilities": {}})["capabilities"]
check("capabilities", (caps["textDocumentSync"]["change"], "completionProvider" in caps, caps["definitionProvider"]), (1, True, True))
c.notify("initialized", {})

diags = c.open(main, text)
check("diagnostics", sorted((d["severity"], d["message"], d["range"]["start"]["line"]) for d in diags),
      [(1, "undefined variable 'missing'", 2), (4, "unused argument 'unusedArg'", 0), (4, "unused binding 'unused'", 3)])
check("unused is unnecessary", [d.get("tags") for d in diags if d["severity"] == 4], [[1], [1]])

check("definition of a let binding", c.at("textDocument/definition", main, text, "foo +"),
      {"uri": main, "range": {"start": {"line": 2, "character": 2}, "end": {"line": 2, "character": 5}}})
check("definition of an argument", c.at("textDocument/definition", main, text, "(pkgs)", 1)["range"]["start"], {"line": 0, "character": 2})
check("definition of a path", c.at("textDocument/definition", main, text, "./other.nix")["uri"], (tmp / "other.nix").as_uri())
check("definition of a directory", c.at("textDocument/definition", main, text, "./dir")["uri"], (tmp / "dir" / "default.nix").as_uri())
check("no definition from with", c.at("textDocument/definition", main, text, "fromWith"), None)

check("references", [r["range"]["start"]["line"] for r in c.at("textDocument/references", main, text, "foo =")], [2, 7])
check("hover on a binding", c.at("textDocument/hover", main, text, "foo +")["contents"]["value"], "`foo`: let binding, line 3")
check("hover on a builtin", c.at("textDocument/hover", main, text, "map (")["contents"]["value"].startswith("`map f list`: built in\n\nApply the function *f*"), True)
check("hover on builtins.x", c.at("textDocument/hover", main, text, "length [", 1)["contents"]["value"].startswith("`builtins.length e`\n\nReturn the length of the list *e*."), True)
check("completion of a builtin, with its doc", [(i["detail"], i["documentation"]["value"][:20]) for i in c.at("textDocument/completion", main, text, "map (", 3) if i["label"] == "map"], [("map f list", "Apply the function *")])
check("hover from with", c.at("textDocument/hover", main, text, "fromWith")["contents"]["value"], "`fromWith`: from `with`")


def labels(items):
    """The labels of a completion's items (a list, or a CompletionList)."""
    if isinstance(items, dict):
        items = items["items"]
    return sorted(i["label"] for i in items)


names = labels(c.at("textDocument/completion", main, text, "foo +"))
check("completion of names in scope", ({"pkgs", "unusedArg", "foo", "unused", "lib", "map", "let"} <= set(names), "x" in names, "__add" in names), (True, False, False))
check("completion of a prefix", labels(c.at("textDocument/completion", main, text, "oo +")), sorted(["foo"] + [n for n in names if n.startswith("f") and n != "foo"]))
check("completion of builtins", "length" in labels(c.at("textDocument/completion", main, text, "ength [", 0)), True)
broken = "let\n  foo = 1;\n  bar = fo\n"
c.open(main, broken)
check("completion while the text doesn't parse", "foo" in labels(c.at("textDocument/completion", main, broken, "fo\n", 2)), True)
check("syntax errors", sorted({d["range"]["start"]["line"] for d in c.diagnostics[main] if d["severity"] == 1}) in ([3], [2, 3]), True)

# Evaluated: attributes of what's in scope, and their docs.
local = (tmp / "local.nix")
ltext = """let
  /** Adds one. */
  inc = x: x + 1;
  s = { a = 1; inherit inc; nested.b = "x"; };
in [ s.a s.inc s.nested.b ]
"""
local.write_text(ltext)
luri = local.as_uri()
c.open(luri, ltext.replace("s.a s.inc", "s. s.inc"))
check("completion of a set's attributes", labels(c.at("textDocument/completion", luri, ltext.replace("s.a s.inc", "s. s.inc"), "s. s.inc", 2)), ["a", "inc", "nested"])
c.open(luri, ltext)
check("hover on an attribute", c.at("textDocument/hover", luri, ltext, "a s.inc", 0)["contents"]["value"], "`s.a`: int `1`")
check("hover on a function, with its doc comment", c.at("textDocument/hover", luri, ltext, "inc s.nested", 0)["contents"]["value"], "`s.inc`: function `function`\n\nAdds one.")
check("hover on a nested attribute", c.at("textDocument/hover", luri, ltext, "b ]", 0)["contents"]["value"], '`s.nested.b`: string `"x"`')

# inherit (s) ...: hover, definition and completion from what is inherited from.
ifile = tmp / "inherit.nix"
itext = """let
  s = { alpha = 1; beta = 2; };
  inherit (s) alpha;
in alpha
"""
ifile.write_text(itext)
iuri = ifile.as_uri()
c.open(iuri, itext)
check("hover on an inherited name", c.at("textDocument/hover", iuri, itext, "alpha\n", 0)["contents"]["value"], "`alpha` (inherited): int `1`")
sfile = tmp / "inherit-set.nix"
stext = "let\n  s = { alpha = 1; beta = 2; };\nin { inherit (s) beta; }\n"
sfile.write_text(stext)
c.open(sfile.as_uri(), stext)
check("hover on a name a set inherits", c.at("textDocument/hover", sfile.as_uri(), stext, "beta; }", 1)["contents"]["value"], "`beta` (inherited): int `2`")
check("definition of a name a set inherits", [r["range"]["start"]["line"] for r in c.at("textDocument/definition", sfile.as_uri(), stext, "beta; }", 1)], [1])
check("definition of an inherited name", [r["range"]["start"]["line"] for r in c.at("textDocument/definition", iuri, itext, "alpha\n", 0)], [1])
partial = itext.replace("inherit (s) alpha;", "inherit (s) b")
c.open(iuri, partial)
check("completion of what to inherit", labels(c.at("textDocument/completion", iuri, partial, "b\n", 1)), ["beta"])

# A call's argument set: the function's arguments.
afile = (tmp / "args.nix").as_uri()
atext = """let
  f = { owner, repo ? "x", hash }: owner;
  g = { __functor = self: x: x; __functionArgs = { a = false; }; };
in [ (f { hash = 1;  }) (g { }) ]
"""
c.open(afile, atext)
items = c.at("textDocument/completion", afile, atext, " }) (g", 1)
check("a call's arguments", sorted((i["sortText"], i["label"], i["detail"]) for i in items), [("0owner", "owner", "required"), ("1repo", "repo", "optional")])
check("an overridable function's arguments", labels(c.at("textDocument/completion", afile, atext, "{ }) ]", 2)), ["a"])

# `with`: completion, hover, definition.
wfile = tmp / "with.nix"
wtext = """let
  s = { alpha = 1; beta = x: x; };
in with s; [ alpha beta ]
"""
wfile.write_text(wtext)
wuri = wfile.as_uri()
partial = wtext.replace("[ alpha beta ]", "[ al beta ]")
c.open(wuri, partial)
check("completion from with", "alpha" in labels(c.at("textDocument/completion", wuri, partial, "al beta", 2)), True)
c.open(wuri, wtext)
check("hover from with", c.at("textDocument/hover", wuri, wtext, "alpha beta ]")["contents"]["value"], "`alpha` (from `with`): int `1`")
check("definition from with", [r["range"]["start"]["line"] for r in c.at("textDocument/definition", wuri, wtext, "alpha beta ]")], [1])
check("definition of an attribute (a function)", [(r["uri"], r["range"]["start"]["line"]) for r in c.at("textDocument/definition", luri, ltext, "inc s.nested", 0)], [(luri, 2)])

# Outline, highlight, rename.
c.open(main, text)
symbols = c.request("textDocument/documentSymbol", {"textDocument": {"uri": main}})
check("symbols", [(x["name"], x["kind"]) for x in symbols], [("foo", 13), ("unused", 16), ("lib", 13), ("a", 8), ("b", 8), ("c", 8), ("d", 8), ("e", 8)])
check("highlight", [(h["range"]["start"]["line"], h["kind"]) for h in c.at("textDocument/documentHighlight", main, text, "foo +")], [(2, 3), (7, 2)])
edit = c.request("textDocument/rename", {"textDocument": {"uri": main}, "position": {"line": 7, "character": 7}, "newName": "bar"})
check("rename", sorted((e["range"]["start"]["line"], e["range"]["start"]["character"], e["newText"]) for e in edit["changes"][main]), [(2, 2, "bar"), (7, 6, "bar")])


def refused(marker, delta=0):
    try:
        c.at("textDocument/prepareRename", main, text, marker, delta)
        return None
    except RuntimeError as e:
        return e.args[0]["message"]


check("no renaming a formal", refused("pkgs,"), "can't rename this: it's an attribute of the function's argument")
check("no renaming what's inherited", refused("lib;"), "can't rename this: it's inherited")

# A text that doesn't parse is repaired for the rest of its analysis.
rfile = (tmp / "broken.nix").as_uri()
rbase = """{ pkgs, ... }:
let
  used = 1;
  unused = 2;
in {
  a = used;
  b = missing;
  c = {
    d = used;
  };
}
"""


def diagnostics_of(text):
    c.open(rfile, text)
    return sorted((d["range"]["start"]["line"] + 1, d["message"]) for d in c.diagnostics[rfile])


check("missing value", diagnostics_of(rbase.replace("a = used;", "a = ")), [
    (1, "unused argument 'pkgs'"), (4, "unused binding 'unused'"),
    (7, "syntax error, unexpected '=', expecting SEMI"), (7, "undefined variable 'missing'")])
check("missing ;", diagnostics_of(rbase.replace("a = used;", "a = used")), [
    (1, "unused argument 'pkgs'"), (4, "unused binding 'unused'"), (7, "syntax error, unexpected '=', expecting SEMI"), (7, "undefined variable 'missing'")])
check("unclosed brace", [m for m in diagnostics_of(rbase.replace("    d = used;\n  };", "    d = used;\n")) if not m[1].startswith("syntax")],
      [(1, "unused argument 'pkgs'"), (4, "unused binding 'unused'"), (7, "undefined variable 'missing'")])
broken = rbase.replace("a = used;", "a = used")
c.open(rfile, broken)
check("references in a broken text", [r["range"]["start"]["line"] for r in c.at("textDocument/references", rfile, broken, "used =")], [2, 5, 8])
check("rename in a broken text", len(c.request("textDocument/rename", {"textDocument": {"uri": rfile}, "position": {"line": 2, "character": 3}, "newName": "u"})["changes"][rfile]), 3)

c.request("shutdown", None)
c.notify("exit", None)
check("exit", c.proc.wait(timeout=30), 0)

# A long evaluation: answered when it times out, or is cancelled, and meanwhile what needs no
# evaluation is answered at once.
c = Client()
c.request("initialize", {"processId": None, "rootUri": tmp.as_uri(), "capabilities": {}, "initializationOptions": {"evalTimeout": 1}})
slow = (tmp / "slow.nix").as_uri()
stext = """let
  deep = n: if n == 0 then 0 else deep (n - 1);
  s = builtins.seq (builtins.foldl' (a: b: a + deep 5000) 0 (builtins.genList (x: x) 1000000)) { a = 1; };
  t = { b = 2; };
in [ s.a t.b ]
"""
c.open(slow, stext)


def send_at(method, uri, text, marker):
    offset = text.index(marker)
    line = text.count("\n", 0, offset)
    c.next_id += 1
    c.send({"id": c.next_id, "method": method, "params": {"textDocument": {"uri": uri},
            "position": {"line": line, "character": offset - (text.rfind("\n", 0, offset) + 1)}}})
    return c.next_id


def answer(id):
    while True:
        msg = c.read()
        if msg.get("id") == id:
            return msg


start = time.time()
slow_id = send_at("textDocument/completion", slow, stext, "a t.b")
check("references meanwhile", len(c.at("textDocument/references", slow, stext, "deep 5000")), 3)
check("answered quickly meanwhile", time.time() - start < 0.9, True)
check("timed out", (labels(answer(slow_id)["result"]), round(time.time() - start)), ([], 1))
check("evaluates after a timeout", labels(c.at("textDocument/completion", slow, stext, "b ]")), ["b"])
cancel_id = send_at("textDocument/completion", slow, stext, "a t.b")
time.sleep(0.2)
c.notify("$/cancelRequest", {"id": cancel_id})
check("cancelled", answer(cancel_id).get("error", {}).get("code"), -32800)
c.request("shutdown", None)
c.notify("exit", None)

# With NIXPKGS (a nixpkgs path): pkgs and lib as a file's arguments, NixOS options in a module.
nixpkgs = os.environ.get("NIXPKGS")
if nixpkgs:
    c = Client()
    nixos = f'import {nixpkgs}/nixos {{ configuration = {{ boot.loader.grub.enable = false; fileSystems."/".device = "x"; system.stateVersion = "25.11"; }}; }}'
    c.request("initialize", {"processId": None, "rootUri": tmp.as_uri(), "capabilities": {},
                             "initializationOptions": {"nixpkgs": f"import {nixpkgs} {{ }}", "nixos": nixos}})
    c.notify("initialized", {})
    module = (tmp / "module.nix").as_uri()
    mtext = """{ config, lib, pkgs, ... }:
let cfg = config.services.nginx; in {
  services.openssh.enable = true;
  networking = {
    firewall.allowedTCPPorts = [ 22 ];
  };
  services.nginx.virtualHosts."example.org" = {
    locations."/".proxyPass = "x";
  };
  config = lib.mkIf true { boot.loader.systemd-boot.enable = true; };
  environment.systemPackages = [ pkgs.hello ];
  x = lib.optional true cfg.virtualHosts;
}
"""

    def complete(old, new, marker, delta):
        text = mtext.replace(old, new)
        c.open(module, text)
        r = c.at("textDocument/completion", module, text, marker, delta)
        return labels(r)

    check("options", complete("services.openssh.enable = true;", "services.ngi", "ngi\n", 3), ["nginx", "ngircd"])
    lfile = (tmp / "inherits.nix").as_uri()
    ltext = "{ lib, ... }:\nlet\n  inherit (lib) optional mkIf;\nin {\n  x = optional true 1;\n}\n"
    c.open(lfile, ltext)
    check("hover on a name inherited from lib", "Return a singleton list" in c.at("textDocument/hover", lfile, ltext, "optional true", 2)["contents"]["value"], True)
    check("definition of a name inherited from lib", c.at("textDocument/definition", lfile, ltext, "optional true", 2)[0]["uri"].endswith("/lib/lists.nix"), True)
    partial = ltext.replace("inherit (lib) optional mkIf;", "inherit (lib) optionalS")
    c.open(lfile, partial)
    check("completion of what to inherit from lib", labels(c.at("textDocument/completion", lfile, partial, "optionalS\n", 9)), ["optionalString"])
    fetch = mtext.replace("x = lib.optional true cfg.virtualHosts;", "x = pkgs.fetchFromGitHub { ow };")
    c.open(module, fetch)
    check("fetchFromGitHub's arguments", labels(c.at("textDocument/completion", module, fetch, "ow }", 2)), ["owner"])
    enum = mtext.replace("services.openssh.enable = true;", 'services.openssh.settings.PermitRootLogin = "";')
    c.open(module, enum)
    check("an option's values", labels(c.at("textDocument/completion", module, enum, '"";', 1)), ["forced-commands-only", "no", "prohibit-password", "without-password", "yes"])
    boolean = mtext.replace("services.openssh.enable = true;", "services.openssh.enable = ;")
    c.open(module, boolean)
    check("a boolean option's values", labels(c.at("textDocument/completion", module, boolean, "= ;", 2)), ["false", "true"])
    check("options on an empty line of a set", {"allowedTCPPorts", "allowedUDPPorts"} <= set(complete("firewall.allowedTCPPorts = [ 22 ];", "firewall = { enable = true;\n    \n  };", "    \n  };", 4)), True)
    check("options at a module's top", "services" in complete("services.openssh.enable = true;", "servi", "ervi\n", 4), True)
    check("options in a set", complete("firewall.allowedTCPPorts = [ 22 ];", "firewall.allowedTC", "allowedTC", 9), ["allowedTCPPortRanges", "allowedTCPPorts"])
    check("options in a submodule", complete('locations."/".proxyPass = "x";', 'locations."/".proxyP', "proxyP", 6), ["proxyPass"])
    check("options under config and mkIf", complete("boot.loader.systemd-boot.enable = true;", "boot.loader.syst", "syst }", 4), ["systemd-boot"])
    check("pkgs", "hello" in complete("pkgs.hello ]", "pkgs.hel ]", "hel ]", 3), True)
    check("a let binding's value", complete("cfg.virtualHosts;", "cfg.virtualH;", "virtualH;", 8), ["virtualHosts"])
    c.open(module, mtext)
    hover = c.at("textDocument/hover", module, mtext, "enable = true", 2)["contents"]["value"]
    check("hover on an option", hover.startswith("`services.openssh.enable`: option `boolean`\n\nWhether to enable the OpenSSH"), True)
    check("hover on lib, with its doc comment", "Return a singleton list or an empty list" in c.at("textDocument/hover", module, mtext, "optional true", 2)["contents"]["value"], True)
    items = c.at("textDocument/completion", module, mtext.replace("pkgs.hello ]", "pkgs.hello ]"), "ello ]", 4)
    hello = [i for i in (items["items"] if isinstance(items, dict) else items) if i["label"] == "hello"][0]
    check("resolve a package", c.request("completionItem/resolve", hello)["detail"].startswith("hello-"), True)
    check("hover on a package", "[homepage](https://www.gnu.org/software/hello/manual/)" in c.at("textDocument/hover", module, mtext, "hello ]", 2)["contents"]["value"], True)
    check("definition of a package", c.at("textDocument/definition", module, mtext, "hello ]", 2)[0]["uri"].endswith("/pkgs/by-name/he/hello/package.nix"), True)
    check("definition of an option", c.at("textDocument/definition", module, mtext, "enable = true", 2)[0]["uri"].endswith("/nixos/modules/services/networking/ssh/sshd.nix"), True)
    wpkgs = mtext.replace("[ pkgs.hello ]", "with pkgs; [ hel ]")
    c.open(module, wpkgs)
    check("completion from with pkgs", "hello" in labels(c.at("textDocument/completion", module, wpkgs, "hel ]", 3)), True)
    c.request("shutdown", None)
    c.notify("exit", None)

    # A flake: what its files are evaluated with.
    fl = tmp / "flake"
    (fl / "shared").mkdir(parents=True)
    (fl / "pkgs").mkdir()
    (fl / "flake.nix").write_text("""{
  inputs.nixpkgs.url = "path:%s";
  outputs = { self, nixpkgs }: let
    system = "x86_64-linux";
    base = { boot.loader.grub.enable = false; fileSystems."/".device = "x"; system.stateVersion = "25.11"; };
  in {
    overlays.default = final: prev: { myHello = prev.hello; };
    legacyPackages.${system} = import nixpkgs { inherit system; overlays = [ self.overlays.default ]; };
    # a package called from a scope of its own (makeScope), with an argument nixpkgs hasn't
    packages.${system}.scoped = (nixpkgs.lib.makeScope self.legacyPackages.${system}.newScope (scope: {
      special = { a = 1; };
      scoped = scope.callPackage ./pkgs/scoped/package.nix { };
    })).scoped;
    nixosConfigurations.alpha = nixpkgs.lib.nixosSystem { inherit system; modules = [ base ./alpha.nix ]; };
    nixosConfigurations.beta = nixpkgs.lib.nixosSystem { inherit system; modules = [ base ./beta.nix ./shared/thing.nix ]; };
    # home-manager's are modules too (as homeManagerConfiguration makes them)
    homeConfigurations.me = nixpkgs.lib.evalModules { modules = [ ./dots/main.nix ({ lib, ... }: {
      options.homeOnly = lib.mkOption { type = lib.types.bool; default = false; description = "Only home has this."; };
      config._module.args.pkgs = import nixpkgs { inherit system; };
    }) ]; };
  };
}
""" % nixpkgs)
    declares = '{ lib, ... }: { options.%s = lib.mkOption { type = lib.types.bool; default = false; description = "%s"; }; }\n'
    (fl / "alpha.nix").write_text(declares % ("alpha.only", "Only alpha has this."))
    (fl / "beta.nix").write_text(declares % ("beta.only", "Only beta has this."))
    (fl / "shared" / "thing.nix").write_text("{ config, ... }: {\n  beta.only = true;\n}\n")
    (fl / "pkgs" / "foo.nix").write_text("{ stdenv, myHello }:\nmyHello.name\n")
    (fl / "pkgs" / "scoped").mkdir()
    (fl / "pkgs" / "scoped" / "package.nix").write_text("{ special, stdenv }:\nstdenv.mkDerivation { name = \"scoped\"; passthru.x = special.a; }\n")
    (fl / "overlay.nix").write_text("final: prev: {\n  x = prev.hello;\n}\n")
    (fl / "dots").mkdir()
    (fl / "dots" / "main.nix").write_text("{ config, pkgs, ... }: {\n  # a comment\n  homeOnly = true;\n}\n")

    def session(settings=None):
        c = Client()
        c.request("initialize", {"processId": None, "rootUri": fl.as_uri(), "capabilities": {}, "initializationOptions": settings or {}})
        c.notify("initialized", {})
        return c

    def complete_in(c, rel, old, new, marker, delta):
        f = fl / rel
        text = f.read_text().replace(old, new)
        c.open(f.as_uri(), text)
        return labels(c.at("textDocument/completion", f.as_uri(), text, marker, delta))

    def evaluated_with(c, rel, marker, delta, old=None, new=None):
        f = fl / rel
        text = f.read_text() if old is None else f.read_text().replace(old, new)
        c.open(f.as_uri(), text)
        return c.at("textDocument/hover", f.as_uri(), text, marker, delta)["contents"]["value"].split("*evaluated with ")[-1].rstrip("*")

    c = session()
    check("the configuration that imports a module", complete_in(c, "shared/thing.nix", "beta.only = true;", "beta.on", "on\n", 2), ["only"])
    check("said in hover", evaluated_with(c, "shared/thing.nix", "only", 1), "`nixosConfigurations.beta` (its `pkgs`)")
    check("a package from the flake's package set", complete_in(c, "pkgs/foo.nix", "myHello.name", "myHello.nam", "nam\n", 3), ["name"])
    check("said in hover", evaluated_with(c, "pkgs/foo.nix", "name", 1), "`legacyPackages.x86_64-linux`")
    foo = fl / "pkgs" / "foo.nix"
    c.open(foo.as_uri(), foo.read_text())
    check("hover on an argument: its value", c.at("textDocument/hover", foo.as_uri(), foo.read_text(), "myHello.name", 2)["contents"]["value"].startswith("`myHello` (argument): package `hello-"), True)
    check("definition of an argument: where its value is", c.at("textDocument/definition", foo.as_uri(), foo.read_text(), "myHello.name", 2)[0]["uri"].endswith("/pkgs/by-name/he/hello/package.nix"), True)
    check("the arguments a package is called with", complete_in(c, "pkgs/scoped/package.nix", "special.a", "special.", "special.;", 8), ["a"])
    check("said in hover", evaluated_with(c, "pkgs/scoped/package.nix", "special.a", 2), "the arguments `packages.x86_64-linux.scoped` calls it with")
    check("an overlay's prev", "hello" in complete_in(c, "overlay.nix", "prev.hello", "prev.hel", "hel;", 3), True)
    check("home-manager's options", complete_in(c, "dots/main.nix", "homeOnly = true;", "homeO", "O\n", 1), ["homeOnly"])
    check("said in hover", evaluated_with(c, "dots/main.nix", "homeOnly", 2), "`homeConfigurations.me` (its `pkgs`)")
    # A module saved with another option: evaluated again.
    (fl / "beta.nix").write_text(declares % ("beta.other", "Beta's other one."))
    c.notify("textDocument/didSave", {"textDocument": {"uri": (fl / "beta.nix").as_uri()}})
    c.open((fl / "beta.nix").as_uri(), (fl / "beta.nix").read_text())
    c.notify("textDocument/didSave", {"textDocument": {"uri": (fl / "beta.nix").as_uri()}})
    check("reloaded after a save", complete_in(c, "shared/thing.nix", "beta.only = true;", "beta.o", "o\n", 1), ["other"])
    c.notify("workspace/didChangeConfiguration", {"settings": {"nix-truffle": {"nixpkgs": "upstream"}}})
    check("the nixpkgs setting", complete_in(c, "pkgs/foo.nix", "myHello.name", "myHello.nam", "nam\n", 3), [])
    c.request("shutdown", None)
    c.notify("exit", None)
    (fl / "beta.nix").write_text(declares % ("beta.only", "Only beta has this."))

    c = session({"configurations": {"shared/**": "flake.nixosConfigurations.alpha",
                                    "dots/**": "{ inherit (flake.homeConfigurations.me) options; config = { }; pkgs = upstream; args.extra = { a = 1; }; }"}})
    check("a configuration by path", complete_in(c, "shared/thing.nix", "beta.only = true;", "alpha.on", "on\n", 2), ["only"])
    check("said in hover", evaluated_with(c, "shared/thing.nix", "only", 1, "beta.only", "alpha.only"), "the `configurations` setting for `shared/**` (its `pkgs`)")
    check("a configuration made by hand", complete_in(c, "dots/main.nix", "homeOnly = true;", "homeO", "O\n", 1), ["homeOnly"])
    check("its arguments", complete_in(c, "dots/main.nix", "{ config, pkgs, ... }: {\n  # a comment\n  homeOnly = true;",
                                       "{ extra, ... }: {\n  x = extra.;", "extra.;", 6), ["a"])
    c.request("shutdown", None)
    c.notify("exit", None)

print(f"{passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
