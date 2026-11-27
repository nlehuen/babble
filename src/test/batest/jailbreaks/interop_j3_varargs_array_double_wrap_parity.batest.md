# Jailbreak : `interop` (`J3`) `array of any?` passed to Java `Object...` varargs diverges between `interpret` and JVM compiler

- **Surface:** `interop`
- **Vector:** `J3` (Java varargs dispatch parity)
- **Severity:** `S0` (Cross-mode behavioral divergence: `interpret` vs `-O0`/`-O1`/`-O2`)
- **Observed:** Passing an `array of any?` (`Object[]`) directly as the single varargs argument to `java.lang.String.format("%s", (array of any?).of("hello"))` unpacks the array as the varargs array in `interpret` (printing `hello`), whereas the JVM compiler (`-O0`, `-O1`, `-O2`) wraps the `Object[]` inside another `Object[]{arr}` (printing `[Ljava.lang.Object;@...`).

## Passing array of any? to Java Object... varargs must agree across interpret and compiled modes

```babble
println(java.lang.String.format("%s", (array of any?).of("hello")))
```

```stdout
hello
```
