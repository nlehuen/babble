# Jailbreak: types T3 flow narrowing invalidation across match if-guard
<!-- Surface: types (T3) | Severity: S1 (Type soundness null leak) | Repro: POST /crib?modes=all&fresh=true | Observed: ESCAPE:NULL in interpret/O0/O1, Wasm illegal cast trap in wasm-O1, silent 2 in O2/wasm-O2 -->

## match if-guard mutating narrowed var must invalidate narrowing in later arms
```babble
def useStr = fn (s: string): int -> (s.length())
var x: string? = "hi"
if x != null then (
    match x
    case is string if (x = null; false) then (println("arm1"))
    else (
        def nonNull: string = x
        try (println(useStr(nonNull)))
        catch e then (println("ESCAPE:NULL"))
    )
)
```
```error
Type error : expected string, got string?
```
