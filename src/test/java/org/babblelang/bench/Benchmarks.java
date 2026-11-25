package org.babblelang.bench;

import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.FileModuleSource;
import org.babblelang.engine.ModuleSource;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.SimpleBindings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A plain warm-up and timing harness comparing interpret, compile -O0, compile -O1 and
 * compile -O2 (docs/design/bip001_bytecode-compiler.md §11, docs/design/bip014_optimization-passes.md).
 * Not a test : run its main method from the repository root, with the test class path
 * (it reads {@code src/test/babble/Stack.ba} and imports the modules of
 * {@code examples/raytracer-argon/lib}).
 *
 * <p>Arguments : an optional filter on the benchmark names, then optionally the modes to run,
 * among {@code interpret}, {@code O0}, {@code O1} and {@code O2} (all of them by default) ; a mode
 * left out prints {@code -}.
 *
 * <p>The modes of one run share a JVM, so a mode profits from what the earlier ones warmed up
 * (the runtime's classes, the compiler's) : to compare two levels closely, run each in a JVM of
 * its own, several times, with a fixed heap ({@code -Xms1g -Xmx1g -XX:+UseParallelGC}, as the
 * test suite runs), for example {@code Benchmarks argon O1} then {@code Benchmarks argon O2}.
 * The argon rows are what the whole ray tracer costs at a level : its render, and compiling its
 * modules before the first pixel.
 */
public final class Benchmarks {
    private static final int WARMUP = 50;
    private static final int RUNS = 20;

    /**
     * A small render of the argon ray tracer's final scene (its 480 marbles behind the BVH, a
     * 64 x 36 image at 4 samples per pixel) : interface dispatch, records, closures and the
     * numeric kernels of {@code lib/math.ba} together, as in the full render. Its imports resolve
     * against {@link #ARGON_FILE}.
     */
    private static final String ARGON = """
            def raytracer = import("lib/raytracer.ba")
            def run = fn (): double -> (
                def rng: raytracer.Rng = raytracer.Rng(2026)
                def world: raytracer.Hittable = raytracer.BVHNode.fromList(raytracer.Scenes.finalScene(rng: rng, gridRadius: 11))
                def cam: raytracer.Camera = raytracer.Camera(aspectRatio: 16.0 / 9.0, imageWidth: 64, samplesPerPixel: 4, maxDepth: 12)
                def sum: double = 0.0
                for j in raytracer.Range(0, cam.imageHeight) then (
                    for i in raytracer.Range(0, cam.imageWidth) then (
                        def c: raytracer.Vec3 = cam.renderPixel(i, j, world, rng)
                        sum += c.x + c.y + c.z
                    )
                )
                sum
            )
            run()
            """;
    private static final Path ARGON_FILE = Path.of("examples/raytracer-argon/benchmark.ba");

    private static final Path ARGON_MAIN = Path.of("examples/raytracer-argon/raytracer.ba");
    private static final int COMPILE_WARMUP = 10;
    private static final int COMPILE_RUNS = 10;

    /**
     * A benchmark : its script, the file it is named as (for its imports) or null, whether to
     * interpret it, and whether to time compiling the program of {@code file} (its main module
     * and every module it imports, each time in a new engine, as {@code -compile} does) rather
     * than running {@code script}.
     */
    private record Bench(String script, Path file, boolean interpret, boolean compileOnly) {
        Bench(String script) {
            this(script, null, true, false);
        }
    }

    private Benchmarks() {
    }

    public static void main(String[] args) throws Exception {
        String stack = Files.readString(Path.of("src/test/babble/Stack.ba"));
        stack = stack.substring(0, stack.indexOf("def test = "));

        Map<String, String> scripts = new LinkedHashMap<>();
        Map<String, Bench> benches = new LinkedHashMap<>();
        scripts.put("fib(25) : calls and arithmetic",
                "def fib = fn (n : int) : int -> ( if n < 2 then ( n ) else ( fib(n - 1) + fib(n - 2) ) ) ; fib(25)");
        scripts.put("while loop : locals and while",
                "def loop = fn (n : int) : int -> ( def i = 0 ; def s = 0 ; while i < n then ( s = s + i ; i = i + 1 ) ; s ) ; loop(300000)");
        scripts.put("tail-recursive sum : self tail calls (BIP024)",
                "def sum = fn (n : int, acc : int) : int -> ( if n == 0 then ( acc ) else ( sum(n - 1, acc + n) ) ) ; sum(300000, 0)");
        scripts.put("Counter.ba closure : captured variables",
                "def counter = fn () -> ( def c = 0 ; fn () -> ( c = c + 1 ) ) ; def c = counter() ; def i = 0\n"
                        + "while i < 200000 then ( c() ; i = i + 1 ) ; c()");
        scripts.put("Stack.ba push/pop : objects and methods",
                stack + "def l : Stack of int = create() ; def i = 0\n"
                        + "while i < 20000 then ( l.push(i) ; i = i + 1 )\n"
                        + "while l.size > 0 then ( l.pop() )");
        scripts.put("ArrayList add/get : Java interop",
                "def l : java.util.ArrayList of int = java.util.ArrayList() ; def i = 0 ; while i < 20000 then ( l.add(i) ; i = i + 1 )\n"
                        + "def s = 0 ; i = 0 ; while i < 20000 then ( s = s + l.get(i) ; i = i + 1 ) ; s");
        // BIP014 optimizer microbenchmarks
        scripts.put("opt : const fold + dead branch + algebraic",
                "def f = fn (n : int) : int -> (\n"
                        + "    def k = (2 + 3) * (10 - 4)\n"
                        + "    def i = 0\n"
                        + "    def s = 0\n"
                        + "    while i < n then (\n"
                        + "        def d = if true then ( (i * 1 + 0) + k ) else ( i * 999 )\n"
                        + "        s = s + d\n"
                        + "        i = i + 1\n"
                        + "    )\n"
                        + "    s\n"
                        + ")\n"
                        + "f(300000)");
        scripts.put("opt : small helper inlining",
                "def sq = fn (x : int) : int -> ( x * x )\n"
                        + "def inc = fn (x : int) : int -> ( x + 1 )\n"
                        + "def run = fn (n : int) : int -> (\n"
                        + "    def i = 0\n"
                        + "    def s = 0\n"
                        + "    while i < n then (\n"
                        + "        s = s + sq(inc(i))\n"
                        + "        i = i + 1\n"
                        + "    )\n"
                        + "    s\n"
                        + ")\n"
                        + "run(300000)");
        scripts.put("opt : loop-invariant pure call (LICM)",
                "axiom java.lang.Math.sqrt: fn pure nothrow\n"
                        + "axiom java.lang.Math.sin: fn pure nothrow\n"
                        + "def heavy = fn (x : double) : double -> ( java.lang.Math.sqrt(x * x + 1.0) + java.lang.Math.sin(x) )\n"
                        + "def run = fn (n : int, a : double) : double -> (\n"
                        + "    def i = 0\n"
                        + "    def s = 0.0\n"
                        + "    while i < n then (\n"
                        + "        s = s + heavy(a) + double(i)\n"
                        + "        i = i + 1\n"
                        + "    )\n"
                        + "    s\n"
                        + ")\n"
                        + "run(200000, 1.5)");
        scripts.put("opt : record Vec3 dot loop",
                "def Vec3 = record (x : double, y : double, z : double) (\n"
                        + "    def plus = fn (o : Vec3) : Vec3 -> ( Vec3(this.x + o.x, this.y + o.y, this.z + o.z) )\n"
                        + "    def dot = fn (o : Vec3) : double -> ( this.x * o.x + this.y * o.y + this.z * o.z )\n"
                        + ")\n"
                        + "def run = fn (n : int) : double -> (\n"
                        + "    def a = Vec3(1.0, 2.0, 3.0)\n"
                        + "    def b = Vec3(0.5, -0.25, 0.125)\n"
                        + "    def i = 0\n"
                        + "    def s = 0.0\n"
                        + "    while i < n then (\n"
                        + "        def c = a + b\n"
                        + "        s = s + c.dot(a)\n"
                        + "        i = i + 1\n"
                        + "    )\n"
                        + "    s\n"
                        + ")\n"
                        + "run(200000)");
        scripts.put("mir : header LICM + GVN/CSE + predicate reorder",
                "def slow = fn nothrow (x : double) : bool -> (\n"
                        + "    java.lang.Math.sin(x) + java.lang.Math.cos(x) > 0.0\n"
                        + ")\n"
                        + "def run = fn (a : int, b : int, x : double) : double -> (\n"
                        + "    def i = 0\n"
                        + "    def s = 0.0\n"
                        + "    while i < a * b + 10000 then (\n"
                        + "        def t = java.lang.Math.abs(java.lang.Math.abs(x))\n"
                        + "        if slow(x + i * 1.0) and (i < 10) then ( s = s + t )\n"
                        + "        i = i + 1\n"
                        + "    )\n"
                        + "    s\n"
                        + ")\n"
                        + "run(40, 1000, -2.5)");

        scripts.forEach((name, script) -> benches.put(name, new Bench(script)));
        // Too slow to interpret 70 times : compiled modes only.
        benches.put("argon ray tracer : 64x36, 4 samples per pixel", new Bench(ARGON, ARGON_FILE, false, false));
        // What -O2 costs before the first pixel : the analysis and the passes of its 15 modules.
        benches.put("argon ray tracer : compile its 15 modules", new Bench(null, ARGON_MAIN, false, true));

        String filter = args.length > 0 ? args[0] : "";
        List<String> modes = args.length > 1
                ? Arrays.asList(args).subList(1, args.length)
                : List.of("interpret", "O0", "O1", "O2");
        System.out.println("| Benchmark | interpret (ms) | -O0 (ms) | -O1 (ms) | -O2 (ms) | -O2/-O0 | -O2/-O1 |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|");
        for (Map.Entry<String, Bench> entry : benches.entrySet()) {
            if (!filter.isEmpty() && !entry.getKey().contains(filter)) {
                continue;
            }
            Bench bench = entry.getValue();
            double interpreted = bench.interpret() && modes.contains("interpret") ? time("interpret", 0, bench) : Double.NaN;
            double opt0 = modes.contains("O0") ? time("compile", 0, bench) : Double.NaN;
            double opt1 = modes.contains("O1") ? time("compile", 1, bench) : Double.NaN;
            double opt2 = modes.contains("O2") ? time("compile", 2, bench) : Double.NaN;
            System.out.printf("| %s | %s | %s | %s | %s | %s | %s |%n",
                    entry.getKey(), millis(interpreted), millis(opt0), millis(opt1), millis(opt2),
                    ratio(opt0, opt2), ratio(opt1, opt2));
        }
    }

    private static String millis(double ms) {
        return Double.isNaN(ms) ? "-" : String.format("%.2f", ms);
    }

    private static String ratio(double base, double opt2) {
        return Double.isNaN(base) || Double.isNaN(opt2) ? "-" : String.format("%.2fx", base / Math.max(opt2, 1e-6));
    }

    /** The median time of an eval, in milliseconds, after warming up. */
    private static double time(String mode, int optLevel, Bench bench) throws Exception {
        if (bench.compileOnly()) {
            return timeCompile(optLevel, bench.file());
        }
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        if (bench.file() != null) {
            engine.getContext().setAttribute(ScriptEngine.FILENAME,
                    bench.file().toAbsolutePath().normalize().toString(), ScriptContext.ENGINE_SCOPE);
        }
        CompiledScript compiled = engine.compile(bench.script());
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

    /** The median time of compiling a program in a new engine, in milliseconds, after warming up. */
    private static double timeCompile(int optLevel, Path main) throws Exception {
        Path file = main.toAbsolutePath().normalize();
        ModuleSource source = FileModuleSource.rooted(file.getParent());
        for (int i = 0; i < COMPILE_WARMUP; i++) {
            compileProgram(optLevel, source, file);
        }
        long[] times = new long[COMPILE_RUNS];
        for (int i = 0; i < COMPILE_RUNS; i++) {
            long start = System.nanoTime();
            compileProgram(optLevel, source, file);
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        return times[COMPILE_RUNS / 2] / 1e6;
    }

    private static void compileProgram(int optLevel, ModuleSource source, Path file) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        engine.compileProgram(source, file.toString());
    }
}
