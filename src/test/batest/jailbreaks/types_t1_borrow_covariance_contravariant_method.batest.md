# Jailbreak : types T1 borrow covariance allows contravariant method ClassCastException
<!-- Surface: types (T1) | Severity: S1 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: compiles with zero as casts and crashes at runtime with parityOk=false — interpret throws Type error : expected string, got int, JVM -O0/-O1/-O2 throw java.lang.ClassCastException (Integer -> String), and wasm-O1/wasm-O2 trap with RuntimeError: illegal cast. -->

## borrowed generic class with contravariant method parameter must not be covariant

```babble
def Sink of T = class (def f: fn (T): int) (
    def push = fn (x: T): int -> (this.f(x))
)
def feed = fn (s: Sink of any): int -> (s.push(42))
def strSink = Sink of string (fn (s: string): int -> (s.length()))
println(feed(strSink))
```

```error
Type error
```
