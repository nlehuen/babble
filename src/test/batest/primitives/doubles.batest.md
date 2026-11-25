# Double literals, scientific notation, NaN, signed zero, infinities, and mixed arithmetic

References : BIP011 (core scalar types), Operators.compareNumbers
Runtime arithmetic inputs go through mutable-state helpers so -O1 cannot constant-fold them away.

```babble
;; babble preamble
var _d = 0.0
var _i = 0
def rtD = fn io (x : double) : double -> ( _d = x ; _d )
def rtI = fn io (x : int) : int -> ( _i = x ; _i )
```

## Case 1

```babble
def a = 0.5
def b = 1.25E2
def c = -2.5
assert(a == 0.5)
assert(b == 125.0)
assert(rtD(a) + rtD(b) + rtD(c) == 123.0)
assert(string(classof(b)) == "java.lang.Double")
```

## Error : Syntax error : unexpected '.'

```babble
def a = .5
```

```error
Syntax error : unexpected '.'
```

## Operators.compareNumbers uses Double.compare (-0.0 < +0.0)

```babble
def nz = rtD(-0.0)
def pz = rtD(0.0)
def inf = rtD(1.0) / pz
def ninf = rtD(-1.0) / pz
assert(not (nz == pz))
assert(nz < pz)
assert(not (pz < nz))
assert(string(rtD(1.0) / nz) == "-Infinity")
assert(string(inf) == "Infinity")
assert(string(ninf) == "-Infinity")
```

## Operators.compareNumbers uses Double.compare (NaN == NaN, NaN > everything)

```babble
def nan = rtD(0.0) / rtD(0.0)
assert(java.lang.Double.isNaN(nan))
assert(nan == nan)
assert(not (nan != nan))
assert(not (nan < 1.0))
assert(nan > 1.0)
assert(not (nan <= 1.0))
assert(nan >= 1.0)
```

## Mixed int and double arithmetic always promotes to double

```babble
assert(string(classof(rtI(1) + rtD(2.0))) == "java.lang.Double")
assert(string(classof(rtD(2.0) - rtI(1))) == "java.lang.Double")
assert(string(classof(rtI(3) * rtD(1.5))) == "java.lang.Double")
assert(string(classof(rtD(5.0) / rtI(2))) == "java.lang.Double")
```

## Mixed int and double comparisons

```babble
assert(rtI(1) < rtD(1.5))
assert(rtI(2) == rtD(2.0))
assert(not (rtD(2.0) != rtI(2)))
assert(rtD(1.5) <= rtI(2))
assert(rtD(2.5) >= rtI(2))
```

## Case 7

```babble
var x = rtD(1.5)
x += rtI(2) ; x -= rtD(0.5) ; x *= rtI(3) ; x /= rtI(2)
assert(x == 4.5)
```
