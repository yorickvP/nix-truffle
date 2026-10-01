# NixOS configurations written in Pkl (pkl-lang.org), read with nix-truffle's builtins.pkl.
#
#   pkl = import ./nixos/pkl.nix { inherit lib; };
#   pkl.schema options      # Pkl source: a property for every option of a NixOS configuration,
#                           # typed, with its description as doc comment
#   pkl.module ./host.pkl   # a NixOS module from a Pkl file that amends that schema
#
# In the schema, an option that isn't set is null (a listing, mapping or object: empty), and
# only the options that are set become definitions. Packages are attribute paths in pkgs
# ("htop", "python3Packages.requests"). Function-typed options aren't in the schema.
{ lib }:
let
  inherit (lib) concatStrings concatStringsSep concatMap filter elemAt hasPrefix isOption;

  literal = s: "\"" + builtins.replaceStrings [ "\\" "\"" "\n" "\r" "\t" ] [ "\\\\" "\\\"" "\\n" "\\r" "\\t" ] s + "\"";
  identifier = n: "`" + builtins.replaceStrings [ "`" ] [ "" ] n + "`";

  # Class names follow option paths ("services.openssh" -> O_services_openssh), with a hash where
  # characters had to be replaced, to keep them distinct.
  className = path:
    let
      joined = concatStringsSep "." path;
      safe = concatStrings (map (c: if builtins.match "[A-Za-z0-9]" c != null then c else "_") (lib.stringToCharacters joined));
    in
    "O_" + safe + lib.optionalString (safe != builtins.replaceStrings [ "." ] [ "_" ] joined) ("_" + builtins.substring 0 6 (builtins.hashString "sha256" joined));

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
  enumType = values:
    let
      value = v: if builtins.isString v then literal v else if builtins.isBool v then lib.boolToString v else if v == null then "null" else toString v;
    in
    if builtins.all builtins.isString values then concatStringsSep "|" (map literal values)
    else "Any(List(${concatStringsSep ", " (map value values)}).contains(this))";

  # The Pkl type of an option type: { type; classes; }, or null for what Pkl can't express
  # (functions). Submodules become classes; types nest `depth` deep at most (some are recursive,
  # like JSON values), and deeper ones are Any.
  maxDepth = 8;
  # kind: how a property of the type is declared (see `option`).
  simple = type: { inherit type; kind = if type == "Any" then "any" else if type == "Dynamic" then "object" else "scalar"; classes = [ ]; };
  union = ts:
    let ok = filter (t: t != null) ts;
    in if ok == [ ] then null
    else if builtins.length ok == 1 then builtins.head ok
    else { type = concatStringsSep "|" (map (t: t.type) ok); kind = "scalar"; classes = concatMap (t: t.classes) ok; };
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
      strings = [ "str" "string" "singleLineStr" "separatedString" "lines" "commas" "envVar" "path" "pathInStore" "pathWith" "package" "shellPackage" "passwdEntry" ];
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
    else if name == "enum" then
      let values = t.functor.payload.values or t.functor.payload or [ ];
      in if values == [ ] then null else simple (enumType values)
    else if builtins.elem name [ "nullOr" "uniq" "unique" ] then element path
    else if name == "listOf" then
      let e = element (path ++ [ "*" ]);
      in if e == null then null else { type = "Listing<${e.type}>"; kind = "object"; inherit (e) classes; }
    else if name == "attrsOf" || name == "lazyAttrsOf" then
      let e = element (path ++ [ "<name>" ]);
      in if e == null then null else { type = "Mapping<String, ${e.type}>"; kind = "object"; inherit (e) classes; }
    else if name == "either" then union [ (pklType (path ++ [ "left" ]) deeper nested.left) (pklType (path ++ [ "right" ]) deeper nested.right) ]
    else if name == "coercedTo" then union [ (pklType (path ++ [ "from" ]) deeper nested.coercedType) (pklType path deeper nested.finalType) ]
    else if name == "functionTo" then null
    # Freeform submodules take any attributes; those of a module class are whole configurations
    # (virtualisation.vmVariant is a NixOS one, with all of these options again).
    else if name == "submodule" then
      if nested ? freeformType || (t.functor.payload.class or null) != null then simple "Dynamic"
      else let c = namespace path deeper (t.getSubOptions [ ]); in { type = c.class; kind = "object"; inherit (c) classes; }
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
      class = className path;
    in
    {
      inherit class members;
      classes = [ "class ${class} {\n${concatStrings (map (m: m.text) members)}}\n" ] ++ concatMap (m: m.classes) members;
    };

  # Objects (listings, mappings, classes, Dynamic) have an empty default, to amend; anything
  # else is null until set.
  option = path: depth: n: o:
    let
      t = pklType path depth o.type;
      declared =
        if t.kind == "object" then t.type
        else if t.kind == "any" then "Any = null"
        else "(${t.type})?";
    in
    if (o.internal or false) || (o.visible or true) == false || t == null then [ ]
    else [ { text = doc o + "  ${identifier n}: ${declared}\n"; inherit (t) classes; } ];

  # Pkl's value of the schema without what isn't set (null, empty): the module's attribute names,
  # which must not depend on `options` (the module system needs them to make `options`).
  prune = x:
    if builtins.isAttrs x then lib.filterAttrs (_: y: y != null && y != { } && y != [ ]) (lib.mapAttrs (_: prune) x)
    else if builtins.isList x then map prune x
    else x;

  # The values, with packages looked up in pkgs (as the options' types say, so only within values).
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
    else if (name == "package" || name == "shellPackage") && builtins.isString x then lib.getAttrFromPath (lib.splitString "." x) pkgs
    else if name == "listOf" && builtins.isList x then map (convert pkgs nested.elemType) x
    else if (name == "attrsOf" || name == "lazyAttrsOf") && builtins.isAttrs x then lib.mapAttrs (_: convert pkgs nested.elemType) x
    else if builtins.elem name [ "nullOr" "uniq" "unique" ] then convert pkgs nested.elemType x
    else if name == "submodule" && !(nested ? freeformType) && builtins.isAttrs x then walk pkgs (t.getSubOptions [ ]) x
    else x;
in
{
  /** The Pkl schema (source) of a NixOS configuration's options. */
  schema = options:
    let root = namespace [ ] 0 options;
    in concatStrings [
      "/// The options of a NixOS configuration (generated by nix-truffle's nixos/pkl.nix).\n"
      "/// Unset options are null (or empty); amend this module to set some.\n"
      "module nixos\n\n"
      (concatStrings (map (m: builtins.replaceStrings [ "\n  " ] [ "\n" ] ("\n" + m.text)) root.members))
      "\n"
      (concatStringsSep "\n" (builtins.tail root.classes))
    ];

  /** A NixOS module from a Pkl file that amends the schema. */
  module = file: { options, pkgs, ... }: walk pkgs options (prune (builtins.pkl { module = file; }));
}
