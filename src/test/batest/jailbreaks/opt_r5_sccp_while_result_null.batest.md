# Jailbreak : opt R5 HirSccpPass folds non-break while loop result to null
<!-- Surface: opt (R5) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Value-returning while loop without break must not fold to null at -O2

```babble
def Pt = record (x: int, y: string) ()
def test = fn (n: int): any? -> (
    var p = Pt(0, "init")
    var i = 0
    def r = while i < n then (
        i += 1
        def z = p.x
        p = Pt(i + z, "hi")
    )
    r
)
def r = test(3)
assert(r is Pt)
```
