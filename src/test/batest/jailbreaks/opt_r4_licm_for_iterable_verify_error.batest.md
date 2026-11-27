# Jailbreak : opt R4 HirLicmPass hoistFromFor triggers CheckClassAdapter / VerifyError
<!-- Surface: opt (R4) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: parityOk=false — interpret, -O0, wasm-O1, and wasm-O2 print 312, while JVM -O1 and -O2 fail ASM CheckClassAdapter ("Expected I, but found .") and throw VerifyError when verify=false. -->

## for-loop with mutating iterable and loop-invariant body arithmetic must verify and match interpret

```babble
def test = fn (n: int): int -> (
    var k = n
    var acc = 0
    for i in (k = n + 10; 0..3) then (
        acc += (k * 7 + 1)
    )
    acc
)
println(test(1))
```

```stdout
312
```
