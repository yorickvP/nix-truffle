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
check("capabilities", (caps["textDocumentSync"], "completionProvider" in caps, caps["definitionProvider"]), (1, True, True))
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
check("hover on a builtin", c.at("textDocument/hover", main, text, "map (")["contents"]["value"], "`map`: built in (`builtins.map`)")
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
check("syntax error", [(d["severity"], d["range"]["start"]["line"]) for d in c.diagnostics[main]], [(1, 3)])

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
    c.request("shutdown", None)
    c.notify("exit", None)

print(f"{passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
