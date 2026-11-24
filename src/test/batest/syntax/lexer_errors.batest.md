# Lexical errors, comments, and string escape handling

References : BIP012 (lexer & grammar), Babble.g4

## Error : Lexical error : token recognition error at: '#'

```babble
def # = 1
```

```error
Lexical error : token recognition error at: '#'
```

## Error : Lexical error : token recognition error at: '"unterminated'

```babble
def s = "unterminated
```

```error
Lexical error : token recognition error at: '"unterminated'
```

## Error : Lexical error : token recognition error at: '"bad\"'

```babble
def s = "bad\"
```

```error
Lexical error : token recognition error at: '"bad\"'
```

## Line comment

```babble
;;( multi
line
comment ;;)
def x = 42
assert(x == 42)
```

## Printed output verifies decoded escape characters directly without tautological string escapes

```babble
println("a\"b\\c")
```

```stdout
a"b\c
```
