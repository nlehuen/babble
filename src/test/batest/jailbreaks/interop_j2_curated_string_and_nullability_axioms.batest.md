# Jailbreak : interop J2 unsound curated axioms for String.contentEquals, String.getChars, and nullable JDK returns
<!-- Surface: interop (J2) | Severity: S2/S1 (io CTF PWNED!) | Repro: POST /crib?modes=all&fresh=true -->

## String.contentEquals must not run io CharSequence inside pure nothrow function

```babble
def EvilCS = class () implements java.lang.CharSequence (
    def length = fn io (): int -> (print("PWNED!"); 1)
    def charAt = fn (i: int): char -> ('a')
    def subSequence = fn (s: int, e: int): java.lang.CharSequence -> (this)
)
def f = fn nothrow (): bool -> ("a".contentEquals(EvilCS()))
def _ = f()
```

```error
io
```

## String.getChars must not mutate frozen char array inside pure function

```babble
def f = fn (arr: frozen (array of char)): char -> (
    "Z".getChars(0, 1, arr, 0)
    arr.get(0)
)
def fr = freeze(array.of('a'))
println(f(fr))
println(fr.get(0))
```

```error
frozen
```

## Character.getName must be typed nullable string?

```babble
def s: string = java.lang.Character.getName(888)
println(s.length())
```

```error
Type error
```

## ProcessBuilder.directory must be typed nullable File?

```babble
def pb = java.lang.ProcessBuilder("echo")
def d: java.io.File = pb.directory()
println(d.getName())
```

```error
Type error
```

## SequencedMap.firstEntry must be typed nullable Entry?

```babble
def m = java.util.LinkedHashMap of (string, int) ()
def e = m.firstEntry()
println(e.getKey())
```

```error
Type error
```
