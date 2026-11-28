# Jailbreak : interop J2 curated Queue.poll, Integer.getInteger, and Map.Entry immutability
<!-- Surface: interop (J2) | Severity: S0/S1/S2 | Repro: POST /crib?modes=all&fresh=true -->

## ArrayDeque.poll must be typed nullable E?

```babble
def q = java.util.ArrayDeque of string ()
def s: string = q.poll()
println(s.length())
```

```error
Type error : expected string, got string?
```

## Integer.getInteger with nullable fallback must return nullable Integer?

```babble
def n: java.lang.Integer = java.lang.Integer.getInteger("babble.no.such.prop", null)
println(n.intValue())
```

```error
Type error : expected java.lang.Integer, got java.lang.Integer?
```

## Map.Entry must not be immutable when live map mutations update its value

```babble
def m = map of (string, int) (entry("a", 1))
def e = m.entrySet().iterator().next()
def fr = freeze(e)
m.put("a", 99)
println(fr.getValue())
```

```error
Cannot freeze expression
```
