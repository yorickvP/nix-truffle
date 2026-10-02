# NixOS configurations written in Pkl (pkl-lang.org), read with nix-truffle's builtins.pkl.
#
#   pkl = import ./nixos/pkl.nix { inherit lib; };
#   pkl.schema options      # Pkl source: a property for every option of a NixOS configuration,
#                           # typed, with its description as doc comment
#   pkl.module ./host.pkl   # a NixOS module from a Pkl file that amends that schema
#   pkl.schemaOf nixos      # the schema of a configuration (nixosSystem's) with Pkl modules
#
# The options that the file sets become definitions, also when they're set to null or to an
# empty listing (builtins.pkl's `defaults = false`); the others don't. Values can be overrides,
# like `module.mkForce(false)` (Nix's lib.mkForce). Packages are values of `import "nix:pkgs"`
# (`pkgs.htop`), calls of Nix functions (`pkgs.callPackage.call(./pkg.nix).call(new {})`), or
# attribute paths in pkgs ("python3Packages.requests"); `import "nix:config"` reads the
# configuration's values, `import "nix:lib"` has nixpkgs' lib (`lib.getExe.call(pkgs.htop).text`),
# and `import "nix:path"` makes Nix paths relative to the Pkl file (`path.call("./secret.age")`).
# Function-typed options take Nix functions (`lib.attrVals.call(new Listing { "requests" })`).
{ lib }:
let
  inherit (lib) concatStrings concatStringsSep concatMap filter elemAt hasPrefix isOption;

  literal = s: "\"" + builtins.replaceStrings [ "\\" "\"" "\n" "\r" "\t" ] [ "\\\\" "\\\"" "\\n" "\\r" "\\t" ] s + "\"";
  identifier = n: "`" + builtins.replaceStrings [ "`" ] [ "" ] n + "`";

  # Class names follow option paths ("services.openssh" -> O_services_openssh; an attrsOf's
  # `<name>` is "name", a listOf's `*` "item"), with a hash where characters had to be replaced,
  # to keep them distinct.
  className = prefix: path:
    let
      joined = concatStringsSep "." (map (c: if c == "<name>" then "name" else if c == "*" then "item" else c) path);
      safe = concatStrings (map (c: if builtins.match "[A-Za-z0-9]" c != null then c else "_") (lib.stringToCharacters joined));
    in
    prefix + safe + lib.optionalString (safe != builtins.replaceStrings [ "." ] [ "_" ] joined) ("_" + builtins.substring 0 6 (builtins.hashString "sha256" joined));

  # The first paragraph of an option's description, as doc comment lines.
  doc = opt:
    let
      d = opt.description or null;
      text = if d == null then "" else if builtins.isString d then d else d.text or "";
      paragraph = builtins.head (lib.splitString "\n\n" (lib.trim text));
    in
    lib.optionalString (paragraph != "") (concatMapLines (l: "  /// ${l}\n") (lib.splitString "\n" paragraph));
  concatMapLines = f: l: concatStrings (map f l);

  # Pkl's literal types are strings: other enums are a constraint.
  enumValue = v: if builtins.isString v then literal v else if builtins.isBool v then lib.boolToString v else if v == null then "null" else toString v;
  enumType = values:
    if builtins.all builtins.isString values then concatStringsSep "|" (map literal values)
    else "Any(List(${concatStringsSep ", " (map enumValue values)}).contains(this))";

  # The Pkl type of an option type: { type; classes; }, or null for what Pkl can't express
  # (functions). Submodules become classes; types nest `depth` deep at most (some are recursive,
  # like JSON values), and deeper ones are Any.
  maxDepth = 8;
  # kind: how a property of the type is declared (see `option`); default: an object's empty value,
  # with defaults for the elements of a listing or a mapping, where those are objects (an
  # Override or an object is a union, which Pkl has no default for).
  # plain: the type without Overrides in it, which an Override's content is checked against.
  simple = type: { inherit type; plain = type; kind = if type == "Any" then "any" else if type == "Dynamic" then "object" else "scalar"; classes = [ ]; default = "new Dynamic {}"; };
  collection = type: plain: e: {
    inherit type plain;
    kind = "object";
    inherit (e) classes;
    default = "new ${type} {${lib.optionalString (e.kind == "object") " default = (_) -> ${e.default} "}}";
  };
  # A union with objects in it (JSON-like values: `oneOf [ str (attrsOf ...) (listOf ...) ]`, as
  # pkgs.formats have) takes Dynamic too: what amending its null default makes, as data is written
  # (`settings { port = 80; hosts { "a" } }`).
  union = ts:
    let
      ok = filter (t: t != null) ts;
      dynamic = lib.optional (builtins.any (t: t.kind == "object") ok && !builtins.any (t: t.type == "Dynamic") ok) (simple "Dynamic");
      all = ok ++ dynamic;
    in if ok == [ ] then null
    else if builtins.length ok == 1 then builtins.head ok
    else { type = concatStringsSep "|" (map (t: t.type) all); plain = concatStringsSep "|" (map (t: t.plain) all); kind = "scalar"; classes = concatMap (t: t.classes) ok; };
  # A long enum (Home Assistant's components) as a type alias, which is there once (named like a
  # class: see `schema`), checked by a function, so that an error doesn't print every value.
  enumAlias = path: values:
    let
      list = concatStringsSep ", " (map enumValue values);
      hash = builtins.substring 0 16 (builtins.hashString "sha256" list);
      token = "@@class:${hash}@@";
      base = if builtins.all builtins.isString values then "String" else "Any";
    in
    simple token // {
      classes = [ {
        inherit hash path;
        name = className "E_" path;
        class = false;
        text = "typealias ${token} = ${base}(${token}_is(this))\nlocal const ${token}_values = Set(${list})\nlocal const function ${token}_is(v) = ${token}_values.contains(v)\n";
      } ];
    };
  pklType = path: depth: t:
    let
      name = t.name or "";
      nested = t.nestedTypes or { };
      deeper = depth + 1;
      element = p: pklType p deeper nested.elemType;
      ints = {
        int = "Int"; signedInt = "Int"; unsignedInt = "Int(isNonNegative)"; positiveInt = "Int(isPositive)";
        unsignedInt8 = "Int(isBetween(0, 255))"; unsignedInt16 = "Int(isBetween(0, 65535))"; unsignedInt32 = "Int(isBetween(0, 4294967295))";
        signedInt8 = "Int(isBetween(-128, 127))"; signedInt16 = "Int(isBetween(-32768, 32767))"; signedInt32 = "Int(isBetween(-2147483648, 2147483647))";
      };
      strings = [ "str" "string" "singleLineStr" "separatedString" "lines" "commas" "envVar" "passwdEntry" ];
    in
    if depth >= maxDepth then simple "Any"
    else if name == "bool" then simple "Boolean"
    else if ints ? ${name} then simple ints.${name}
    else if name == "intBetween" then
      let m = builtins.match "integer between (-?[0-9]+) and (-?[0-9]+).*" (t.description or "");
      in simple (if m == null then "Int" else "Int(isBetween(${elemAt m 0}, ${elemAt m 1}))")
    else if name == "float" then simple "Float"
    else if hasPrefix "number" name then simple "Number"
    else if name == "nonEmptyStr" then simple "String(!isEmpty)"
    else if builtins.elem name strings || hasPrefix "strMatching" name then simple "String"
    else if name == "package" || name == "shellPackage" then simple "Package"
    else if builtins.elem name [ "path" "pathInStore" "pathWith" ] then simple "Path"
    else if name == "enum" then
      let values = t.functor.payload.values or t.functor.payload or [ ];
      in if values == [ ] then null
      else if builtins.stringLength (enumType values) <= 200 then simple (enumType values)
      else enumAlias path values
    else if builtins.elem name [ "nullOr" "uniq" "unique" ] then element path
    else if name == "listOf" then
      let e = element (path ++ [ "*" ]);
      in if e == null then null else collection "Listing<${e.type}>" "Listing<${e.plain}>" e
    else if name == "attrsOf" || name == "lazyAttrsOf" then
      let e = element (path ++ [ "<name>" ]);
      in if e == null then null else collection "Mapping<String, ${overridable e}>" "Mapping<String, ${e.plain}>" e
    else if name == "either" then union [ (pklType (path ++ [ "left" ]) deeper nested.left) (pklType (path ++ [ "right" ]) deeper nested.right) ]
    else if name == "coercedTo" then union [ (pklType (path ++ [ "from" ]) deeper nested.coercedType) (pklType path deeper nested.finalType) ]
    else if name == "functionTo" then simple "NixValue"
    # Freeform submodules take any attributes; those of a module class are whole configurations
    # (virtualisation.vmVariant is a NixOS one, with all of these options again).
    else if name == "submodule" then
      if nested ? freeformType || (t.functor.payload.class or null) != null then simple "Dynamic"
      else let c = namespace path deeper (t.getSubOptions [ ]); in { type = c.class; plain = c.class; kind = "object"; inherit (c) classes; default = "new ${c.class} {}"; }
    else simple "Any";

  # A class for a set of options: a property for each option, and for each set of options in it.
  namespace = path: depth: opts:
    let
      members = concatMap (n:
        let o = opts.${n}; p = path ++ [ n ]; in
        if hasPrefix "_" n then [ ]
        else if isOption o then option p depth n o
        else if builtins.isAttrs o then
          let c = namespace p depth o; in [ { text = "  ${identifier n}: ${c.class}\n"; inherit (c) classes; } ]
        else [ ]) (builtins.attrNames opts);
      body = concatStrings (map (m: m.text) members);
      # Classes are named by their content until `schema` names them, so that identical ones
      # (as systemd's units have) are one class.
      hash = builtins.substring 0 16 (builtins.hashString "sha256" body);
      class = "@@class:${hash}@@";
    in
    {
      inherit class members;
      classes = [ { inherit hash path; name = className "O_" path; text = "class ${class} {\n${body}}\n"; class = true; } ] ++ concatMap (m: m.classes) members;
    };

  # An option's value, or an Override of one (as are the values of attrsOf, which the module
  # system pushes overrides into). Pkl checks an Override's content, unless its type has classes
  # (`new Mapping { ... }` makes no instances of them).
  overridable = t:
    if t.kind == "any" then t.type
    else "${t.type}|Override${lib.optionalString (!builtins.any (c: c.class) t.classes) "(content is (${t.plain}))"}";

  # Objects (listings, mappings, classes, Dynamic) have an empty default, to amend; anything
  # else is null until set.
  option = path: depth: n: o:
    let
      t = pklType path depth o.type;
      declared =
        if t.kind == "object" then "(${overridable t}) = ${t.default}"
        else if t.kind == "any" then "Any = null"
        else "(${overridable t})?";
    in
    if (o.internal or false) || (o.visible or true) == false || t == null then [ ]
    else [ { text = doc o + "  ${identifier n}: ${declared}\n"; inherit (t) classes; } ];

  # Overrides, as lib.mkOverride and lib.mkOrder make them: data that the module system reads.
  overrides = ''
    /// A definition with a priority (`mkForce`, `mkDefault`: Nix's `lib.mkOverride`) or an order
    /// (`mkBefore`, `mkAfter`: `lib.mkOrder`). In an amending module: `module.mkForce(false)`.
    class Override {
      _type: "override"|"order"
      priority: Int
      content: Any
    }

    function mkOverride(p: Int, c): Override = new { _type = "override"; priority = p; content = c }
    function mkForce(c): Override = mkOverride(50, c)
    function mkDefault(c): Override = mkOverride(1000, c)
    function mkOrder(p: Int, c): Override = new { _type = "order"; priority = p; content = c }
    function mkBefore(c): Override = mkOrder(500, c)
    function mkAfter(c): Override = mkOrder(1500, c)

    /// A Nix value: from `import "nix:..."` (a function, a set), or a call of a Nix function.
    typealias NixValue = Module|Typed(getClass().simpleName == "NixCall")
    /// A package: from `import "nix:pkgs"` (`pkgs.htop`), a call of a Nix function
    /// (`pkgs.callPackage.call(...)`), or an attribute path in pkgs (`"python3Packages.requests"`).
    typealias Package = String|NixValue
    /// A path: a string, or a Nix path (`path.call("./secret.age")`, relative to the Pkl file).
    typealias Path = String|NixValue
  '';

  # The values, with packages looked up in pkgs (as the options' types say, so only within values).
  # The attribute names are the file's (builtins.pkl's `defaults = false`): they must not depend on
  # `options`, `config` or `pkgs`, which the module system makes with them.
  walk = pkgs: opts: x:
    lib.mapAttrs (n: y:
      let o = opts.${n} or null; in
      if o == null then y
      else if isOption o then convert pkgs o.type y
      else if builtins.isAttrs y then walk pkgs o y
      else y) x;

  convert = pkgs: t: x:
    let
      name = t.name or "";
      nested = t.nestedTypes or { };
    in
    if x == null then null
    else if builtins.isAttrs x && builtins.elem (x._type or null) [ "override" "order" ] then x // { content = convert pkgs t x.content; }
    else if (name == "package" || name == "shellPackage") && builtins.isString x then lib.getAttrFromPath (lib.splitString "." x) pkgs
    else if name == "listOf" && builtins.isList x then map (convert pkgs nested.elemType) x
    else if (name == "attrsOf" || name == "lazyAttrsOf") && builtins.isAttrs x then lib.mapAttrs (_: convert pkgs nested.elemType) x
    else if builtins.elem name [ "nullOr" "uniq" "unique" ] then convert pkgs nested.elemType x
    else if name == "submodule" && !(nested ? freeformType) && builtins.isAttrs x then walk pkgs (t.getSubOptions [ ]) x
    else x;
  # The Pkl schema (source) of a NixOS configuration's options.
  # Each class is there once, named after one of its paths: preferably one of a set of them (an
  # attrsOf's `<name>` or a listOf's `*`, like services.nginx.virtualHosts.<name>, rather than
  # services.davis.nginx, a vhost too), then the shortest, then the first.
  schema = options:
    let
      root = namespace [ ] 0 options;
      classes = builtins.tail root.classes;
      each = c: if c.path == [ ] then 1 else if builtins.elem (lib.last c.path) [ "<name>" "*" ] then 0 else 1;
      shorter = a: b:
        let la = builtins.stringLength a.name; lb = builtins.stringLength b.name;
        in each a < each b || each a == each b && (la < lb || la == lb && a.name < b.name);
      names = builtins.listToAttrs (map (c: { name = c.hash; value = c.name; }) (builtins.sort shorter classes));
      unique = builtins.attrValues (builtins.listToAttrs (map (c: { name = c.hash; value = c; }) classes));
      text = concatStrings [
        "/// The options of a NixOS configuration (generated by nix-truffle's nixos/pkl.nix).\n"
        "/// Amend this module to set some: the options that are set (also to null, or to an empty\n"
        "/// listing) are definitions, and unset ones are null (or empty) here.\n"
        "module nixos\n\n"
        overrides
        "\n"
        (concatStrings (map (m: builtins.replaceStrings [ "\n  " ] [ "\n" ] ("\n" + m.text)) root.members))
        "\n"
        (concatStringsSep "\n" (map (c: c.text) (builtins.sort (a: b: names.${a.hash} < names.${b.hash}) unique)))
      ];
    in
    concatStrings (map (x: if builtins.isList x then names.${builtins.head x} else x) (builtins.split "@@class:([0-9a-f]+)@@" text));
in
{
  /** The Pkl schema (source) of a NixOS configuration's options. */
  inherit schema;

  /**
    The schema of a configuration (an evalModules result, like nixosSystem's) that has Pkl
    modules: of the configuration without them (specialArgs.pklSchema), as option types can depend
    on the configuration, and reading the Pkl files needs the schema they amend.
  */
  schemaOf = configuration: schema (configuration.extendModules { specialArgs.pklSchema = true; }).options;

  /** A NixOS module from a Pkl file that amends the schema. */
  module = file: lib.setDefaultModuleLocation file (args@{ options, config, lib, pkgs, ... }:
    if args.pklSchema or false then { }
    else walk pkgs options (builtins.pkl {
      module = file;
      defaults = false;
      # path: a Nix path relative to the Pkl file (one in the store, as a ./path in Nix is)
      nix = { inherit config lib pkgs; path = p: dirOf file + "/${p}"; };
    }));
}
