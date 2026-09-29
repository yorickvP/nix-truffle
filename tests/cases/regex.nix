[
  (builtins.match "a(b+)c" "abbbc")
  (builtins.match "a(b+)c" "abbbd")
  (builtins.match "([[:alpha:]]+)-([0-9]+)" "foo-123")
  (builtins.split "(,)" "a,b,c")
  (builtins.split "[[:space:]]+" "a  b c")
]
