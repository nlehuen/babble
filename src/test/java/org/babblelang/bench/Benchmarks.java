package org.babblelang.bench;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;

import javax.script.CompiledScript;
import javax.script.SimpleBindings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A plain warm-up and timing harness comparing the two modes (design doc, section 11).
 * Not a test : run its main method from the repository root, with the test class path
 * (it reads {@code src/test/babble/Stack.ba}).
 */
public final class Benchmarks {
    private static final int WARMUP = 50;
    private static final int RUNS = 20;

    private Benchmarks() {
    }

    public static void main(String[] args) throws Exception {
        String stack = Files.readString(Path.of("src/test/babble/Stack.ba"));
        stack = stack.substring(0, stack.indexOf("def test = "));

        Map<String, String> scripts = new LinkedHashMap<>();
        scripts.put("fib(25) : calls and arithmetic",
                "def fib = (n) -> ( if n < 2 then ( n ) else ( fib(n - 1) + fib(n - 2) ) ) ; fib(25)");
        scripts.put("while loop : locals and while",
                "def loop = (n) -> ( def i = 0 ; def s = 0 ; while i < n then ( s = s + i ; i = i + 1 ) ; s ) ; loop(300000)");
        scripts.put("Counter.ba closure : captured variables",
                "def counter = () -> ( def c = 0 ; () -> ( c = c + 1 ) ) ; def c = counter() ; def i = 0\n"
                        + "while i < 200000 then ( c() ; i = i + 1 ) ; c()");
        scripts.put("Stack.ba push/pop : objects and methods",
                stack + "def l = stack.create() ; def i = 0\n"
                        + "while i < 20000 then ( l.push(i) ; i = i + 1 )\n"
                        + "while l.size > 0 then ( l.pop() )");
        scripts.put("ArrayList add/get : Java interop",
                "def l = java.util.ArrayList() ; def i = 0 ; while i < 20000 then ( l.add(i) ; i = i + 1 )\n"
                        + "def s = 0 ; i = 0 ; while i < 20000 then ( s = s + l.get(i) ; i = i + 1 ) ; s");

        System.out.println("| Benchmark | interpret (ms) | compile (ms) | speed-up |");
        System.out.println("|---|---:|---:|---:|");
        for (Map.Entry<String, String> script : scripts.entrySet()) {
            double interpreted = time("interpret", script.getValue());
            double compiled = time("compile", script.getValue());
            System.out.printf("| %s | %.1f | %.1f | %.1fx |%n", script.getKey(), interpreted, compiled, interpreted / compiled);
        }
    }

    /** The median time of an eval, in milliseconds, after warming up. */
    private static double time(String mode, String script) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        CompiledScript compiled = engine.compile(script);
        for (int i = 0; i < WARMUP; i++) {
            compiled.eval(new SimpleBindings());
        }
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            compiled.eval(new SimpleBindings());
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        return times[RUNS / 2] / 1e6;
    }
}
