let
  pos = __curPos;
in [
  pos.line pos.column (baseNameOf pos.file)
  (let __curPos = 5; in builtins.isAttrs __curPos)
  { inherit (__curPos) line; }
]
