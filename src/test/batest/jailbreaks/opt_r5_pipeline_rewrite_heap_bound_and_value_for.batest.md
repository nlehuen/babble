# Jailbreak : opt R5 PipelineRewrite hoists heap-reading pure bound and clamps value-returning for
<!-- Surface: opt (R5) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->

## Range clamping must not hoist heap-reading pure bound mutated in loop body

```babble
def Box = class (var lim: int) ()
def getLim = fn (b: Box): int -> (b.lim)
def test = fn (n: int): int -> (
    def b = Box(2)
    var acc = 0
    for x in 0..n then (
        if x <= getLim(b) then (
            acc += x
            b.lim += 1
        )
    )
    acc
)
assert(test(10) == 55)
```

## PipelineRewrite must not clamp a value-returning for loop

```babble
def f = fn (n: int): string? -> (
    for x in 0..n then (
        if x <= 3 then ("v" + x)
    )
)
assert(f(5) == null)
```
