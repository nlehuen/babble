# Syntax error diagnostics

References : BIP012 (predicate-free LL(1)/LL(2) grammar), Babble.g4

## Error : Syntax error : unexpected '='

```babble
def = 1
```

```error
Syntax error : unexpected '='
```

## Error : Syntax error : unexpected end of input

```babble
def x =
```

```error
Syntax error : unexpected end of input
```

## `true ( 1 )` parses as a call condition, so the missing `then` is reported at EOF

```babble
if true ( 1 )
```

```error
Syntax error : unexpected end of input
```

## Error : Syntax error : unexpected '->'

```babble
def f = fn (a : int -> ( a )
```

```error
Syntax error : unexpected '->'
```

## Error : Syntax error : unexpected ')'

```babble
1 + )
```

```error
Syntax error : unexpected ')'
```

## Double trailing '?' on single-arg generic is a syntax error (BIP012-D2)

```babble
def x : list of int?? = null
```

```error
Syntax error : unexpected '?'
```
