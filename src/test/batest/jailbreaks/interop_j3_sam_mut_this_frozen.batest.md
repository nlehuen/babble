# Jailbreak : interop J3 Java SAM callback ignores mutThis on frozen receiver
<!-- Surface: interop (J3) | Severity: S2 | Repro: POST /crib?modes=all&fresh=true -->

## List.replaceAll must reject frozen UnaryOperator with mut apply

```babble
def MutOp = class (var v: int) implements java.util.function.UnaryOperator of int (
    def apply = fn mut (x: int): int -> (this.v = 99; x)
)
def corruptFrozen = fn io (op: frozen MutOp): int -> (
    def xs = list of int (1)
    xs.replaceAll(op)
    op.v
)
def f = freeze(MutOp(1))
def before = f.v
def after = corruptFrozen(f)
println("" + before + ":" + after)
```

```error
frozen
```
