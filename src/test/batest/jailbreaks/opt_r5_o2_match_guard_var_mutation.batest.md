# Jailbreak: opt R5 O2 and wasm-O2 miscompile var mutation inside match if-guard
<!-- Surface: opt (R5) | Severity: S0 (Critical optimizer wrong-code parity divergence) | Repro: POST /crib?modes=all&fresh=true | Observed: PARITY DIVERGENCE interpret,O0,O1,wasm-O1=<2> vs O2,wasm-O2=<1> -->

## var reassigned in failing match if-guard must remain updated in else branch
```babble
var x = 1
match x
case 1 if (x = 2; false) then (println("arm1"))
else (println(x))
```
```stdout
2
```
