let
  js = builtins.polyglotEval "js";
  add = js "(x, y) => x + y";
  mod = import ./fib.js;
  pkg = {
    pname = "hello";
    version = "2.12";
    deps = [ "glibc" "gettext" ];
    # never forced: JS only reads the members it touches
    broken = throw "JS must not force this";
  };
in {
  # foreign function, all arguments passed in one call
  sum = add 40 2;
  # a JS object: `.` reads members, application executes them
  fib = mod.fib 20;
  greeting = mod.greet "nix";
  described = mod.describe pkg;
  # JS calls back into a curried Nix lambda
  callback = mod.callNix (a: b: a * 10 + b) 4 2;
  # foreign arrays behave like lists
  squares = map (x: x * x) (js "[1, 2, 3, 4]");
  jsObject = js "({ answer: 42, nested: { list: [1, 'two'] } })";
  mathMax = (js "Math").max 3 9 4;
}
