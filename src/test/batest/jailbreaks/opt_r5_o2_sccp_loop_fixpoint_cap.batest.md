# Jailbreak : opt R5 -O2 HirSccpPass loop fixpoint cap leaves modified vars falsely constant
<!-- Surface: opt (R5) | Severity: S0 | Repro: POST /crib?modes=all&fresh=true -->
<!-- Observed: parityOk=false — interpret, -O0, -O1, and wasm-O1 print 99, while -O2 and wasm-O2 print 0 (HirSccpPass stops loop fixpoint iteration at MAX_LOOP_FIXPOINT_ITERS=32 without falling back to widening all loop-modified variables). -->

## loop shift register longer than 32 must not constant-fold v0 to 0 at -O2

```babble
def shift = fn (): int -> (
    var v0 = 0; var v1 = 0; var v2 = 0; var v3 = 0; var v4 = 0; var v5 = 0; var v6 = 0; var v7 = 0
    var v8 = 0; var v9 = 0; var v10 = 0; var v11 = 0; var v12 = 0; var v13 = 0; var v14 = 0; var v15 = 0
    var v16 = 0; var v17 = 0; var v18 = 0; var v19 = 0; var v20 = 0; var v21 = 0; var v22 = 0; var v23 = 0
    var v24 = 0; var v25 = 0; var v26 = 0; var v27 = 0; var v28 = 0; var v29 = 0; var v30 = 0; var v31 = 0
    var v32 = 0; var v33 = 0
    for i in 0..40 then (
        v0 = v1; v1 = v2; v2 = v3; v3 = v4; v4 = v5; v5 = v6; v6 = v7; v7 = v8
        v8 = v9; v9 = v10; v10 = v11; v11 = v12; v12 = v13; v13 = v14; v14 = v15; v15 = v16
        v16 = v17; v17 = v18; v18 = v19; v19 = v20; v20 = v21; v21 = v22; v22 = v23; v23 = v24
        v24 = v25; v25 = v26; v26 = v27; v27 = v28; v28 = v29; v29 = v30; v30 = v31; v31 = v32
        v32 = v33; v33 = 99
    )
    v0
)
println(shift())
```

```stdout
99
```
