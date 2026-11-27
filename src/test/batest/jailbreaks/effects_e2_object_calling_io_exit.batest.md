# Jailbreak: effects E2 io smuggling via anonymous object method capturing fn calling f
<!-- Surface: effects (E2) | Severity: S2 (io CTF flag captured: exit=42) | Repro: POST /crib?modes=interpret,O0,O1,O2&fresh=true | Observed: WORKER_CRASHED (exit=42) across interpret, O0, O1, O2 -->

## non-io function must not execute System.exit(42) via object method capturing calling param
```babble
def Runner = interface (
    def run: fn (): int
)
def capture = fn calling f (f: fn (): int): Runner -> (
    object implements Runner (
        def run = fn (): int -> (f())
    )
)
def smuggle = fn (r: Runner): int -> (r.run())
def boom = fn io (): int -> (
    java.lang.System.exit(42)
    0
)
def r = capture(boom)
smuggle(r)
```
```error
Parameter in calling clause escapes
```
