package org.babblelang.tests;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.script.CompiledScript;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import org.babblelang.analysis.CompilationTarget;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.MapModuleSource;
import org.babblelang.format.Formatter;
import org.babblelang.ir.IrObserver;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTarget;
import org.babblelang.ir.IrTier;
import org.babblelang.ir.hir.HirCanonicalPrinter;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirPrinter;
import org.babblelang.wasm.WasmCompiler;
import org.babblelang.wasm.WasmProgramLinker;
import org.babblelang.wasm.WatPrinter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * Verifies every Babble exhibit in {@code docs/design/bip045_the-optimization-zoo.md} ({@code
 * BIP045}) and tests end-to-end compiler determinism:
 *
 * <ol>
 *   <li>Every {@code Before} and {@code After} code snippet parses cleanly, is formatter-clean,
 *       passes {@code HirVerifier} and ASM {@code CheckClassAdapter}, and executes without error
 *       across {@code interpret}, {@code -O0}, {@code -O1}, and {@code -O2}.
 *   <li>Every HIR-level {@code Before}/{@code After} pair compiles down to the same canonical HIR
 *       at {@code -O2} (and each representation/lowering/FBIP exhibit triggers its expected
 *       compiler transformation).
 *   <li>The compiler is strictly deterministic across repeated sequential and concurrent
 *       compilations for canonical HIR, raw HIR, JVM {@code .class} bytecode, and WebAssembly 3.0
 *       {@code .wasm} binaries.
 * </ol>
 */
public class OptimizationZooTestCase {
    private static final Path ZOO_DOC = Path.of("docs/design/bip045_the-optimization-zoo.md");

    private static final String SHARED_STUBS =
            "def expensivePureCheck = fn (x: int): bool -> (x * x + 1 > 10)\n"
                    + "def isExpensive = fn (x: int): bool -> (x * x + 1 > 10)\n";

    private static final String VEC2_MODULE =
            "export def Vec2 = record (x: double, y: double) (\n"
                    + "    def dot = fn (o: Vec2): double -> (this.x * o.x + this.y * o.y)\n"
                    + ")\n";

    private static final Pattern TOP_DEF_HEADER =
            Pattern.compile("(?m)^(?=def\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=)");
    private static final Pattern DEF_NAME =
            Pattern.compile("^def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=");
    private static final Pattern FN_EFFECTS_TAG =
            Pattern.compile("(?m)^(\\s*fn\\s+\\S+\\s*\\([^)]*\\)\\s*->\\s*\\S+)\\s*\\{[^}]*\\}");
    private static final Pattern VAR_TOKEN = Pattern.compile("\\bvar\\(([^)]+)\\)");

    /**
     * Exhibits whose {@code After} snippet illustrates a lower-level representation, FBIP, or
     * backend mechanism that has no distinct Babble source syntax at the HIR level. Each receives
     * an explicit transformation assertion in {@link #verifySpecializedExhibit(ZooPair)}.
     */
    private static final Set<String> SPECIALIZED_EXHIBITS =
            Set.of(
                    "Z6.2",
                    "Z6.3",
                    "Z6.4",
                    "Z6.5",
                    "Z6.6",
                    "Z9.2",
                    "Z9.4",
                    "Z9.6",
                    "Z9.7",
                    "Showcase B");

    /** Exhibits whose {@code Before} snippet intentionally triggers a static compiler warning. */
    private static final Map<String, String> EXPECTED_BEFORE_WARNINGS =
            Map.of(
                    "Z1.2", "Division by zero : always throws java.lang.ArithmeticException",
                    "Z4.4", "try block cannot throw; catch clause is unreachable");

    record ZooPair(String id, String title, String before, String after) {
        @Override
        public String toString() {
            return id + " — " + title;
        }
    }

    static List<ZooPair> zooPairs() throws IOException {
        List<String> lines = Files.readAllLines(ZOO_DOC);
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, String> befores = new LinkedHashMap<>();
        Map<String, String> afters = new LinkedHashMap<>();
        String curId = null;
        String curRole = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("### ")) {
                String heading = line.substring(4).trim();
                int sep = heading.indexOf(" — ");
                if (sep > 0) {
                    curId = heading.substring(0, sep).trim();
                    titles.put(curId, heading.substring(sep + 3).trim());
                } else {
                    curId = null;
                }
                curRole = null;
            } else if ("**Before**".equals(line.trim())) {
                curRole = "Before";
            } else if ("**After**".equals(line.trim())) {
                curRole = "After";
            } else if ("```babble".equals(line.trim()) && curId != null && curRole != null) {
                StringBuilder buf = new StringBuilder();
                i++;
                while (i < lines.size() && !"```".equals(lines.get(i).trim())) {
                    if (!buf.isEmpty()) {
                        buf.append('\n');
                    }
                    buf.append(lines.get(i));
                    i++;
                }
                if ("Before".equals(curRole)) {
                    befores.put(curId, buf.toString());
                } else {
                    afters.put(curId, buf.toString());
                }
            }
        }

        List<ZooPair> pairs = new ArrayList<>();
        for (Map.Entry<String, String> e : titles.entrySet()) {
            String id = e.getKey();
            String b = befores.get(id);
            String a = afters.get(id);
            if (b != null && a != null) {
                pairs.add(new ZooPair(id, e.getValue(), b, a));
            }
        }
        return pairs;
    }

    // ------------------------------------------------------------------ 1. Doc structure & hygiene

    @Test
    public void testZooStructureLinksAndCodeFences() throws IOException {
        String text = Files.readString(ZOO_DOC);
        List<ZooPair> pairs = zooPairs();
        Assertions.assertEquals(
                49, pairs.size(), "Expected 47 numbered exhibits + 2 showcases in BIP045");

        // Directory table IDs must match ### Z#.# headings 1-to-1
        List<String> tableIds = new ArrayList<>();
        Matcher tm = Pattern.compile("(?m)^\\|\\s*\\*\\*(Z\\d+\\.\\d+)\\*\\*\\s*\\|").matcher(text);
        while (tm.find()) {
            tableIds.add(tm.group(1));
        }
        List<String> headingIds =
                pairs.stream().map(ZooPair::id).filter(id -> id.startsWith("Z")).toList();
        Assertions.assertEquals(
                tableIds, headingIds, "Master Zoo Directory IDs must match exhibit headings");

        // Only babble and mermaid code fences are allowed (no HIR, bytecode, or WAT blocks)
        Matcher fm = Pattern.compile("(?m)^```(\\w*)").matcher(text);
        while (fm.find()) {
            String lang = fm.group(1);
            Assertions.assertTrue(
                    lang.isEmpty() || "babble".equals(lang) || "mermaid".equals(lang),
                    "Unexpected code fence language in BIP045 : " + lang);
        }

        // All relative markdown links must resolve to existing files
        Matcher lm = Pattern.compile("\\[[^\\]]+\\]\\(([^)]+)\\)").matcher(text);
        Path docDir = ZOO_DOC.toAbsolutePath().getParent();
        while (lm.find()) {
            String link = lm.group(1);
            if (link.startsWith("http://") || link.startsWith("https://") || link.startsWith("#")) {
                continue;
            }
            String filePart = link.contains("#") ? link.substring(0, link.indexOf('#')) : link;
            Path resolved = docDir.resolve(filePart).normalize();
            Assertions.assertTrue(
                    Files.exists(resolved), "Broken relative link in BIP045 : " + link);
        }
    }

    // ------------------------------------------------------------------ 2. Every Zoo sample passes

    @ParameterizedTest(name = "{0}")
    @MethodSource("zooPairs")
    public void testAllZooCodeSamplesPass(ZooPair pair) throws Exception {
        // Check pure-SLL parsing and formatter idempotence on both raw snippets
        assertFormatterClean(pair.id() + " Before", pair.before());
        assertFormatterClean(pair.id() + " After", pair.after());

        String beforeSrc = buildRunnableSource(pair.before(), Map.of());
        String afterSrc = buildRunnableSource(pair.after(), extractTopDefs(pair.before()));

        // Execute in interpret, -O0, -O1, and -O2 with HirVerifier & CheckClassAdapter
        String expectedBeforeWarn = EXPECTED_BEFORE_WARNINGS.get(pair.id());
        for (String mode : List.of("interpret", "O0", "O1", "O2")) {
            evalSample(pair.id() + " Before (" + mode + ")", beforeSrc, mode, expectedBeforeWarn);
            evalSample(pair.id() + " After (" + mode + ")", afterSrc, mode, null);
        }
    }

    // ------------------------------------------------------------------ 3. Before/After -O2 HIR

    @ParameterizedTest(name = "{0}")
    @MethodSource("zooPairs")
    public void testBeforeAfterPairsCompileToSameHirAtO2(ZooPair pair) throws Exception {
        if (SPECIALIZED_EXHIBITS.contains(pair.id())) {
            verifySpecializedExhibit(pair);
            return;
        }

        String beforeSrc = buildRunnableSource(pair.before(), Map.of());
        String afterSrc = buildRunnableSource(pair.after(), extractTopDefs(pair.before()));

        HirModule beforeHir;
        HirModule afterHir;
        if ("Z5.2".equals(pair.id())) {
            beforeHir = compileProgramHirAtO2(beforeSrc, CompilationTarget.JVM);
            afterHir = compileProgramHirAtO2(afterSrc, CompilationTarget.JVM);
        } else {
            beforeHir = compileSingleHir(beforeSrc, 2);
            afterHir = compileSingleHir(afterSrc, 2);
        }

        List<IrTarget> targets = selectComparisonTargets(pair, beforeHir, afterHir);
        Assertions.assertFalse(targets.isEmpty(), "No comparison targets found for " + pair.id());

        for (IrTarget target : targets) {
            String rawBefore = HirCanonicalPrinter.print(beforeHir, target);
            String rawAfter = HirCanonicalPrinter.print(afterHir, target);
            String normBefore = normalizeForStructuralEquivalence(rawBefore);
            String normAfter = normalizeForStructuralEquivalence(rawAfter);
            Assertions.assertEquals(
                    normAfter,
                    normBefore,
                    "Mismatch in -O2 canonical HIR for "
                            + pair.id()
                            + " target="
                            + target
                            + "\n--- RAW BEFORE ---\n"
                            + rawBefore
                            + "\n--- RAW AFTER ---\n"
                            + rawAfter);
        }
    }

    private void verifySpecializedExhibit(ZooPair pair) throws Exception {
        String beforeSrc = buildRunnableSource(pair.before(), Map.of());
        String afterSrc = buildRunnableSource(pair.after(), extractTopDefs(pair.before()));
        HirModule o0 = compileSingleHir(beforeSrc, 0);
        HirModule o2 = compileSingleHir(beforeSrc, 2);
        HirModule afterO2 = compileSingleHir(afterSrc, 2);

        switch (pair.id()) {
            case "Z6.2" -> {
                String beforeO0 = HirCanonicalPrinter.print(o0, new IrTarget.Fn("manhattan"));
                String beforeO2 = HirCanonicalPrinter.print(o2, new IrTarget.Fn("manhattan"));
                Assertions.assertTrue(beforeO0.contains("(match (record-new Point"), beforeO0);
                Assertions.assertFalse(beforeO2.contains("(match"), beforeO2);
                Assertions.assertFalse(beforeO2.contains("(record-new"), beforeO2);
                Assertions.assertTrue(beforeO2.contains("(+:int var(a) var(b))"), beforeO2);
            }
            case "Z6.3" -> {
                String beforeO0 = HirCanonicalPrinter.print(o0, new IrTarget.Fn("walk"));
                String beforeO2 = HirCanonicalPrinter.print(o2, new IrTarget.Fn("walk"));
                Assertions.assertTrue(beforeO0.contains("(record-new Vec2"), beforeO0);
                Assertions.assertFalse(beforeO2.contains("(record-new"), beforeO2);
                Assertions.assertTrue(beforeO2.contains("$sr_p_0"), beforeO2);
                Assertions.assertTrue(beforeO2.contains("$sr_p_1"), beforeO2);
            }
            case "Showcase B" -> {
                String beforeO0 = HirCanonicalPrinter.print(o0, new IrTarget.Fn("march"));
                String beforeO2 = HirCanonicalPrinter.print(o2, new IrTarget.Fn("march"));
                Assertions.assertTrue(beforeO0.contains("(record-new Vec2"), beforeO0);
                Assertions.assertFalse(beforeO2.contains("(record-new"), beforeO2);
                Assertions.assertTrue(beforeO2.contains("(def $licm0 (jcall Math.sqrt"), beforeO2);
                Assertions.assertTrue(beforeO2.contains("$sr_pos_0"), beforeO2);
                Assertions.assertTrue(beforeO2.contains("$sr_pos_1"), beforeO2);
            }
            case "Z6.4" -> {
                // Before synthesizes heavyScore$unboxed matching After's heavyScore_unboxed
                String unboxedBefore =
                        HirCanonicalPrinter.print(o2, new IrTarget.Fn("heavyScore$unboxed"));
                String unboxedAfter =
                        HirCanonicalPrinter.print(afterO2, new IrTarget.Fn("heavyScore_unboxed"));
                String normalizedAfter =
                        unboxedAfter
                                .replace("heavyScore_unboxed", "heavyScore$unboxed")
                                .replace("a_x", "a$x")
                                .replace("a_y", "a$y")
                                .replace("a_z", "a$z")
                                .replace("b_x", "b$x")
                                .replace("b_y", "b$y")
                                .replace("b_z", "b$z");
                Assertions.assertEquals(
                        normalizeForStructuralEquivalence(normalizedAfter),
                        normalizeForStructuralEquivalence(unboxedBefore),
                        "Z6.4 $unboxed companion body mismatch");
                String callerBefore = HirCanonicalPrinter.print(o2, new IrTarget.Fn("caller"));
                Assertions.assertTrue(
                        callerBefore.contains("call-fn heavyScore$unboxed"),
                        "Z6.4 caller should dispatch to heavyScore$unboxed :\n" + callerBefore);
                Assertions.assertFalse(
                        callerBefore.contains("record-new"),
                        "Z6.4 caller should not allocate Vec3 :\n" + callerBefore);
            }
            case "Z6.5" -> {
                String beforeO0 = HirCanonicalPrinter.print(o0, new IrTarget.Fn("pushItem"));
                String beforeO2 = HirCanonicalPrinter.print(o2, new IrTarget.Fn("pushItem"));
                Assertions.assertTrue(
                        beforeO0.contains("(core-new list from=(move var(xs)))"), beforeO0);
                Assertions.assertTrue(
                        beforeO2.contains("(core-new list$reuse from=(move var(xs)))"), beforeO2);
            }
            case "Z6.6" -> {
                String beforeO0 = HirCanonicalPrinter.print(o0, new IrTarget.Fn("bump"));
                String beforeO2 = HirCanonicalPrinter.print(o2, new IrTarget.Fn("bump"));
                Assertions.assertTrue(beforeO0.contains("(record-new Bucket"), beforeO0);
                Assertions.assertTrue(beforeO2.contains("(record-reuse var(b) Bucket"), beforeO2);
            }
            case "Z9.2" -> {
                String beforeO2 = HirCanonicalPrinter.print(o2, new IrTarget.Fn("countEven"));
                Assertions.assertTrue(beforeO2.contains("(for-range i ..<"), beforeO2);
                Assertions.assertFalse(beforeO2.contains("contains"), beforeO2);
                Assertions.assertFalse(beforeO2.contains("(range"), beforeO2);
            }
            case "Z9.4" -> {
                String beforeO2 = HirCanonicalPrinter.print(o2, new IrTarget.Fn("sumTo"));
                Assertions.assertTrue(beforeO2.contains("(tail-call sumTo"), beforeO2);
            }
            case "Z9.6" -> {
                // Verify JVM jump table for dayType
                CompiledBabbleScript compiled = compileScript(beforeSrc, 2);
                boolean foundSwitch = false;
                for (byte[] bytes : compiled.getClasses().values()) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(bytes).accept(cn, 0);
                    for (MethodNode mn : cn.methods) {
                        if (mn.name.contains("dayType")) {
                            for (AbstractInsnNode insn : mn.instructions) {
                                if (insn.getOpcode() == Opcodes.TABLESWITCH
                                        || insn.getOpcode() == Opcodes.LOOKUPSWITCH) {
                                    foundSwitch = true;
                                }
                            }
                        }
                    }
                }
                Assertions.assertTrue(foundSwitch, "Z9.6 dayType should emit a JVM switch table");

                // Verify Wasm 3.0 single-implementer devirtualization for measure(s: Shape)
                BabbleScriptEngine wasmEngine = newEngine(2);
                MapModuleSource src = new MapModuleSource().put("main.ba", beforeSrc);
                List<BabbleScriptEngine.HirProgramModule> mods =
                        wasmEngine.compileProgramToHir(src, "main.ba", CompilationTarget.WASM_JS);
                List<WasmProgramLinker.ModuleInput> inputs =
                        mods.stream()
                                .map(m ->
                                        new WasmProgramLinker.ModuleInput(
                                                m.id().key(),
                                                m.id().displayName(),
                                                m.hir(),
                                                m.staticImportKeys()))
                                .toList();
                WasmCompiler.CompileResult wasmRes =
                        WasmCompiler.compile(
                                inputs,
                                new WasmCompiler.Options(
                                        CompilationTarget.WASM_JS, true, true, false, false),
                                "z96.wasm");
                Assertions.assertTrue(wasmRes.ok(), "Z9.6 should compile cleanly to Wasm 3.0");
                String wat = WatPrinter.print(wasmRes.module());
                // Single-implementer measure() emits direct `call` to Circle.area, not `call_ref`
                int measureIdx = wat.indexOf("measure");
                Assertions.assertTrue(measureIdx >= 0, "Expected measure function in WAT");
                int funcEnd = wat.indexOf("\n  (func ", measureIdx + 1);
                String measureWat =
                        funcEnd > measureIdx
                                ? wat.substring(measureIdx, funcEnd)
                                : wat.substring(measureIdx);
                Assertions.assertFalse(
                        measureWat.contains("call_ref"),
                        "Z9.6 Wasm measure() should devirtualize single-implementer Shape.area :\n"
                                + measureWat);
            }
            case "Z9.7" -> {
                // Verify HirSlotAllocator reduces JVM maxLocals at -O1/-O2 vs -O0
                int localsO0 = findMethodMaxLocals(compileScript(beforeSrc, 0), "staged");
                int localsO2 = findMethodMaxLocals(compileScript(beforeSrc, 2), "staged");
                Assertions.assertTrue(
                        localsO2 < localsO0,
                        "Z9.7 slot coloring should reduce maxLocals at -O2 ("
                                + localsO2
                                + ") vs -O0 ("
                                + localsO0
                                + ")");
            }
            default -> Assertions.fail("Unhandled specialized exhibit : " + pair.id());
        }
    }

    // ------------------------------------------------------------------ 4. Compiler determinism

    @Test
    public void testCompilerDeterminismAcrossHirJvmAndWasm() throws Exception {
        String multiFeatureProgram =
                """
                def Vec2 = record (x: double, y: double) (
                    def plus = fn (o: Vec2): Vec2 -> (Vec2(this.x + o.x, this.y + o.y))
                    def dot = fn (o: Vec2): double -> (this.x * o.x + this.y * o.y)
                )
                def Box = class (v0: int) (
                    var v = v0
                    def setAndDouble = fn mut (nv: int): int -> (
                        this.v = nv
                        this.v + this.v
                    )
                )
                def Shape = interface (
                    def area: fn (): double
                )
                def Circle = record (r: double) implements Shape (
                    def area = fn (): double -> (3.141592653589793 * this.r * this.r)
                )
                def encode = fn axiom pure nothrow monotone injective (x: int): double -> (
                    x * 10.0 + 5.0
                )
                def combineMax = fn associative (a: int, b: int): int -> (
                    if a > b then (a) else (b)
                )
                def mix = fn commutative (a: int, b: int): int -> (
                    a * a + b * b + 1
                )
                def heavyDot = fn (a: Vec2, b: Vec2): double -> (
                    if a.x < 0.0 then (return 0.0)
                    a.x * b.x + a.y * b.y
                )

                export def zoo = fn (
                    n: int, flag: bool, unique b1: Box, unique b2: Box, s: Shape
                ): double -> (
                    def scores = list of double ()
                    for x in 0..500 then (scores.add(encode(x)))
                    var total = 0.0
                    for s2 in scores then (
                        if s2 <= encode(20) then (total += s2)
                    )
                    var m = 0
                    for i in 0..<n then (m = combineMax(m, i * 3 + 1))
                    var pos = Vec2(0.0, 0.0)
                    for i in 0..<n then (pos = pos.plus(Vec2(1.0, 2.0)))
                    def g = mix(n, 3) + mix(3, n)
                    def x0 = b1.v
                    b2.v = 20
                    def bv = x0 + b1.v + b2.setAndDouble(7)
                    def hd = heavyDot(Vec2(1.0, 2.0), Vec2(3.0, 4.0))
                    def tagCode = match n
                        case 0 | 1 | 2 then (10)
                        case 3 | 4 | 5 then (20)
                        case 6 | 7 | 8 then (30)
                        else (40)
                    total + m + pos.dot(pos) + g + bv + hd + s.area() + tagCode
                )
                assert(zoo(5, true, Box(1), Box(2), Circle(2.0)) > 0.0)
                """;

        MapModuleSource src = new MapModuleSource().put("det/main.ba", multiFeatureProgram);

        // A. Sequential determinism across 5 fresh engines at -O0, -O1, -O2 (HIR + JVM + Wasm)
        for (int opt : new int[] {0, 1, 2}) {
            String baselineCanonical = null;
            String baselineRawHir = null;
            Map<String, byte[]> baselineJvmClasses = null;
            byte[] baselineWasm = null;

            for (int run = 0; run < 5; run++) {
                // HIR determinism
                BabbleScriptEngine hirEng = newEngine(opt);
                List<BabbleScriptEngine.HirProgramModule> hirMods =
                        hirEng.compileProgramToHir(src, "det/main.ba", CompilationTarget.JVM);
                HirModule mainHir = hirMods.get(0).hir();
                String canon = HirCanonicalPrinter.print(mainHir, new IrTarget.Module());
                String raw = HirPrinter.print(mainHir);
                if (baselineCanonical == null) {
                    baselineCanonical = canon;
                    baselineRawHir = raw;
                } else {
                    Assertions.assertEquals(
                            baselineCanonical,
                            canon,
                            "Non-deterministic canonical HIR at -O" + opt + " run " + run);
                    Assertions.assertEquals(
                            baselineRawHir,
                            raw,
                            "Non-deterministic raw HIR at -O" + opt + " run " + run);
                }

                // JVM bytecode determinism (named module => deterministic class names & type IDs)
                BabbleScriptEngine jvmEng = newEngine(opt);
                List<BabbleScriptEngine.ProgramModule> progMods =
                        jvmEng.compileProgram(src, "det/main.ba");
                Map<String, byte[]> classes = new TreeMap<>();
                for (BabbleScriptEngine.ProgramModule pm : progMods) {
                    classes.putAll(pm.script().getClasses());
                }
                if (baselineJvmClasses == null) {
                    baselineJvmClasses = classes;
                } else {
                    Assertions.assertEquals(
                            baselineJvmClasses.keySet(),
                            classes.keySet(),
                            "JVM class set changed at -O" + opt + " run " + run);
                    for (String clsName : baselineJvmClasses.keySet()) {
                        Assertions.assertArrayEquals(
                                baselineJvmClasses.get(clsName),
                                classes.get(clsName),
                                "Non-deterministic JVM bytecode for "
                                        + clsName
                                        + " at -O"
                                        + opt
                                        + " run "
                                        + run);
                    }
                }

                // WebAssembly 3.0 binary determinism (at -O1 and -O2)
                if (opt >= 1) {
                    BabbleScriptEngine wasmEng = newEngine(opt);
                    List<BabbleScriptEngine.HirProgramModule> wasmMods =
                            wasmEng.compileProgramToHir(
                                    src, "det/main.ba", CompilationTarget.WASM_JS);
                    List<WasmProgramLinker.ModuleInput> inputs =
                            wasmMods.stream()
                                    .map(m ->
                                            new WasmProgramLinker.ModuleInput(
                                                    m.id().key(),
                                                    m.id().displayName(),
                                                    m.hir(),
                                                    m.staticImportKeys()))
                                    .toList();
                    WasmCompiler.CompileResult wres =
                            WasmCompiler.compile(
                                    inputs,
                                    new WasmCompiler.Options(
                                            CompilationTarget.WASM_JS, true, true, false, false),
                                    "det.wasm");
                    Assertions.assertTrue(
                            wres.ok(), "Wasm compile failed: " + wres.portabilityErrors());
                    if (baselineWasm == null) {
                        baselineWasm = wres.wasmBytes();
                    } else {
                        Assertions.assertArrayEquals(
                                baselineWasm,
                                wres.wasmBytes(),
                                "Non-deterministic Wasm binary at -O" + opt + " run " + run);
                    }
                }
            }
        }

        // B. Concurrent multi-threaded compilation determinism at -O2
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Map<String, byte[]>>> tasks = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                tasks.add(() -> {
                    BabbleScriptEngine eng = newEngine(2);
                    List<BabbleScriptEngine.ProgramModule> mods =
                            eng.compileProgram(src, "det/main.ba");
                    Map<String, byte[]> out = new TreeMap<>();
                    for (BabbleScriptEngine.ProgramModule pm : mods) {
                        out.putAll(pm.script().getClasses());
                    }
                    return out;
                });
            }
            List<Future<Map<String, byte[]>>> futures = pool.invokeAll(tasks);
            Map<String, byte[]> first = futures.get(0).get();
            for (int t = 1; t < futures.size(); t++) {
                Map<String, byte[]> other = futures.get(t).get();
                Assertions.assertEquals(first.keySet(), other.keySet());
                for (String cls : first.keySet()) {
                    Assertions.assertArrayEquals(
                            first.get(cls),
                            other.get(cls),
                            "Concurrent compilation bytecode drift on thread " + t + " for " + cls);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // C. Zoo-wide -O2 HIR determinism: every Zoo Before sample compiles identically twice
        for (ZooPair pair : zooPairs()) {
            String sample = buildRunnableSource(pair.before(), Map.of());
            HirModule h1 =
                    "Z5.2".equals(pair.id())
                            ? compileProgramHirAtO2(sample, CompilationTarget.JVM)
                            : compileSingleHir(sample, 2);
            HirModule h2 =
                    "Z5.2".equals(pair.id())
                            ? compileProgramHirAtO2(sample, CompilationTarget.JVM)
                            : compileSingleHir(sample, 2);
            Assertions.assertEquals(
                    HirCanonicalPrinter.print(h1, new IrTarget.Module()),
                    HirCanonicalPrinter.print(h2, new IrTarget.Module()),
                    "Non-deterministic -O2 HIR for Zoo exhibit " + pair.id());
        }
    }

    // ------------------------------------------------------------------ Helpers

    private static void assertFormatterClean(String label, String code) throws Exception {
        String formatted = Formatter.format(code, label).stripTrailing();
        Assertions.assertEquals(
                code.stripTrailing(),
                formatted,
                "Zoo snippet is not formatter-clean (" + label + ")");
    }

    private static BabbleScriptEngine newEngine(int optLevel) {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        engine.put(BabbleCompiler.VERIFY, true);
        engine.put(
                BabbleScriptEngine.MODULE_SOURCE,
                new MapModuleSource().put("vec2.ba", VEC2_MODULE));
        return engine;
    }

    private static void evalSample(String label, String source, String mode, String expectedWarning)
            throws Exception {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        StringWriter errs = new StringWriter();
        engine.getContext().setErrorWriter(errs);
        engine.put(
                BabbleScriptEngine.MODULE_SOURCE,
                new MapModuleSource().put("vec2.ba", VEC2_MODULE));
        if ("interpret".equals(mode)) {
            engine.put(BabbleScriptEngine.MODE, "interpret");
        } else {
            engine.put(BabbleScriptEngine.MODE, "compile");
            engine.put(BabbleCompiler.OPT_LEVEL, Integer.parseInt(mode.substring(1)));
            engine.put(BabbleCompiler.VERIFY, true);
        }
        CompiledScript script = engine.compile(source);
        if (script instanceof CompiledBabbleScript cbs) {
            for (byte[] bytes : cbs.getClasses().values()) {
                StringWriter sw = new StringWriter();
                CheckClassAdapter.verify(
                        new ClassReader(bytes),
                        cbs.getScriptClass().getClassLoader(),
                        false,
                        new PrintWriter(sw));
                Assertions.assertEquals(
                        "", sw.toString(), label + " bytecode verify failed:\n" + sw);
            }
        }
        script.eval(new SimpleBindings());
        if (expectedWarning != null) {
            Assertions.assertTrue(
                    errs.toString().contains(expectedWarning),
                    label + " expected warning '" + expectedWarning + "', got:\n" + errs);
        } else {
            Assertions.assertEquals("", errs.toString(), label + " emitted diagnostics:\n" + errs);
        }
    }

    private static CompiledBabbleScript compileScript(String source, int optLevel)
            throws ScriptException {
        BabbleScriptEngine engine = newEngine(optLevel);
        CompiledScript compiled = engine.compile(source);
        return (CompiledBabbleScript) compiled;
    }

    private static HirModule compileSingleHir(String source, int optLevel) throws ScriptException {
        BabbleScriptEngine engine = newEngine(optLevel);
        HirModule[] captured = {null};
        engine.put(
                BabbleCompiler.IR_OBSERVER,
                new IrObserver() {
                    @Override
                    public boolean wants(IrTier tier, int level, String stage) {
                        return tier == IrTier.HIR && IrSnapshot.STAGE_FINAL.equals(stage);
                    }

                    @Override
                    public void onSnapshot(IrSnapshot snapshot) {
                        captured[0] = (HirModule) snapshot.ir();
                    }
                });
        engine.compile(source);
        Assertions.assertNotNull(captured[0], "Expected final HIR snapshot");
        return captured[0];
    }

    private static HirModule compileProgramHirAtO2(String mainSrc, CompilationTarget target)
            throws ScriptException {
        BabbleScriptEngine engine = newEngine(2);
        MapModuleSource source =
                new MapModuleSource().put("vec2.ba", VEC2_MODULE).put("main.ba", mainSrc);
        List<BabbleScriptEngine.HirProgramModule> mods =
                engine.compileProgramToHir(source, "main.ba", target);
        return mods.get(0).hir();
    }

    private static int findMethodMaxLocals(CompiledBabbleScript cbs, String fnName) {
        for (byte[] bytes : cbs.getClasses().values()) {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            for (MethodNode mn : cn.methods) {
                if (mn.name.endsWith("$" + fnName + "$spec")) {
                    return mn.maxLocals;
                }
            }
        }
        throw new AssertionError("No $spec method found for " + fnName);
    }

    private static Map<String, String> extractTopDefs(String source) {
        Map<String, String> defs = new LinkedHashMap<>();
        for (String chunk : TOP_DEF_HEADER.split(source)) {
            Matcher m = DEF_NAME.matcher(chunk);
            if (m.find()) {
                defs.put(m.group(1), chunk.trim());
            }
        }
        return defs;
    }

    private static String buildRunnableSource(String code, Map<String, String> inheritedDefs) {
        List<String> parts = new ArrayList<>();
        if (code.contains("expensivePureCheck") || code.contains("isExpensive")) {
            parts.add(SHARED_STUBS);
        }
        if (!inheritedDefs.isEmpty()) {
            Map<String, String> ownDefs = extractTopDefs(code);
            Set<String> needed = new LinkedHashSet<>();
            boolean progress = true;
            while (progress) {
                progress = false;
                StringBuilder hay = new StringBuilder(code);
                for (String n : needed) {
                    hay.append('\n').append(inheritedDefs.get(n));
                }
                for (String name : inheritedDefs.keySet()) {
                    if (!ownDefs.containsKey(name)
                            && !needed.contains(name)
                            && Pattern.compile("\\b" + Pattern.quote(name) + "\\b")
                                    .matcher(hay)
                                    .find()) {
                        needed.add(name);
                        progress = true;
                    }
                }
            }
            for (String name : inheritedDefs.keySet()) {
                if (needed.contains(name)) {
                    parts.add(inheritedDefs.get(name));
                }
            }
        }
        parts.add(code);
        return String.join("\n\n", parts) + "\n";
    }

    private static List<IrTarget> selectComparisonTargets(
            ZooPair pair, HirModule beforeHir, HirModule afterHir) {
        Map<String, String> afterDefs = extractTopDefs(pair.after());
        List<IrTarget> targets = new ArrayList<>();
        // Check class methods explicitly defined in After (e.g. Z3.3 Box.setAndDouble)
        if ("Z3.3".equals(pair.id())) {
            targets.add(new IrTarget.Method("Box", "setAndDouble"));
        }
        for (String name : afterDefs.keySet()) {
            if (hasFunction(beforeHir, name) && hasFunction(afterHir, name)) {
                targets.add(new IrTarget.Fn(name));
            }
        }
        if (targets.isEmpty()) {
            targets.add(new IrTarget.TopLevel());
        }
        return targets;
    }

    private static boolean hasFunction(HirModule mod, String name) {
        for (var fn : mod.functions().values()) {
            if (name.equals(fn.name()) && !fn.isCompiledClassMethod()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalizes only semantically inert canonical-HIR formatting artifacts when comparing
     * compiler-rewritten HIR against human-written Babble HIR:
     *
     * <ol>
     *   <li>Strips the pre-optimization {@code {effects}} annotation on the {@code fn} header line
     *       (since {@code EffectChecker} runs before optimization).
     *   <li>Unwraps transparent {@code (block ...)} grouping and statement-position {@code (box
     *       ...)} wrappers around {@code if}/{@code set}/{@code jcall} (which {@code HirEmitter}
     *       suppresses via {@code valueNeeded = false}).
     *   <li>Alpha-normalizes local variable names in order of first definition ({@code $cse0} vs
     *       {@code cse0}, {@code $licm0} vs {@code licm0}, {@code $sr_p_0} vs {@code p_x}, {@code
     *       $max#0} vs {@code clampedLo}, {@code $i#2} vs {@code idx}), leaving parameter names,
     *       opcodes, constants, fields, and call targets completely untouched.
     * </ol>
     */
    private static String normalizeForStructuralEquivalence(String canonicalHir) {
        // 1. Strip pre-optimization inferred {effects} from fn header
        String s = FN_EFFECTS_TAG.matcher(canonicalHir).replaceAll("$1");

        // 2. Flatten indentation into a single token-clean S-expression stream
        List<String> lines =
                Arrays.stream(s.split("\n")).map(String::trim).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) {
            return "";
        }
        String header = lines.get(0);
        String body = String.join("\n", lines.subList(1, lines.size()));

        // Parse S-expression list, unwrap transparent (block ...) inside statement sequences and
        // statement-position (box ...), then alpha-normalize locally defined variable names.
        List<SExpr> stmts = parseSExprs(body);
        stmts = normalizeStmtList(stmts, false);
        Map<String, String> alpha = new HashMap<>();
        int[] nextIdx = {0};
        stmts = alphaNormalizeList(stmts, alpha, nextIdx);

        StringBuilder out = new StringBuilder(header);
        for (SExpr stmt : stmts) {
            out.append('\n').append("  ").append(stmt.render());
        }
        return out.toString();
    }

    // Minimal S-expression AST for structural canonical-HIR normalization
    private sealed interface SExpr permits SAtom, SList {
        String render();
    }

    private record SAtom(String text) implements SExpr {
        @Override
        public String render() {
            return text;
        }
    }

    private record SList(List<SExpr> elems) implements SExpr {
        @Override
        public String render() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < elems.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(elems.get(i).render());
            }
            return sb.append(')').toString();
        }
    }

    private static List<SExpr> parseSExprs(String input) {
        List<SExpr> out = new ArrayList<>();
        int[] pos = {0};
        while (true) {
            skipWs(input, pos);
            if (pos[0] >= input.length()) break;
            out.add(parseOne(input, pos));
        }
        return out;
    }

    private static SExpr parseOne(String s, int[] pos) {
        skipWs(s, pos);
        if (s.charAt(pos[0]) == '(') {
            pos[0]++;
            List<SExpr> elems = new ArrayList<>();
            while (true) {
                skipWs(s, pos);
                if (pos[0] >= s.length() || s.charAt(pos[0]) == ')') {
                    if (pos[0] < s.length()) pos[0]++;
                    break;
                }
                elems.add(parseOne(s, pos));
            }
            return new SList(elems);
        }
        int start = pos[0];
        if (s.charAt(start) == '"') {
            pos[0]++;
            while (pos[0] < s.length()) {
                char c = s.charAt(pos[0]++);
                if (c == '\\' && pos[0] < s.length()) pos[0]++;
                else if (c == '"') break;
            }
            return new SAtom(s.substring(start, pos[0]));
        }
        int parenDepth = 0;
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '(') {
                // Keep var(x) / convert[double] style atoms intact
                parenDepth++;
                pos[0]++;
            } else if (c == ')') {
                if (parenDepth == 0) break;
                parenDepth--;
                pos[0]++;
            } else if (Character.isWhitespace(c) && parenDepth == 0) {
                break;
            } else {
                pos[0]++;
            }
        }
        return new SAtom(s.substring(start, pos[0]));
    }

    private static void skipWs(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static boolean isHead(SExpr expr, String head) {
        return expr instanceof SList l
                && !l.elems().isEmpty()
                && l.elems().get(0) instanceof SAtom a
                && head.equals(a.text());
    }

    private static List<SExpr> normalizeStmtList(List<SExpr> stmts, boolean allStmtPos) {
        List<SExpr> flat = new ArrayList<>();
        for (int i = 0; i < stmts.size(); i++) {
            boolean isStmtPos = allStmtPos || i < stmts.size() - 1;
            SExpr e = normalizeExpr(stmts.get(i), isStmtPos);
            if (isHead(e, "block")) {
                List<SExpr> inner = ((SList) e).elems();
                flat.addAll(normalizeStmtList(inner.subList(1, inner.size()), isStmtPos));
            } else {
                flat.add(e);
            }
        }
        return flat;
    }

    private static SExpr normalizeExpr(SExpr expr, boolean stmtPos) {
        if (!(expr instanceof SList l) || l.elems().isEmpty()) {
            return expr;
        }
        // Unwrap statement-position (box e) and single-element (block e)
        if (stmtPos && isHead(l, "box") && l.elems().size() == 2) {
            return normalizeExpr(l.elems().get(1), true);
        }
        if (isHead(l, "block")) {
            List<SExpr> body = normalizeStmtList(l.elems().subList(1, l.elems().size()), stmtPos);
            if (body.size() == 1) {
                return body.get(0);
            }
            List<SExpr> rebuilt = new ArrayList<>(body.size() + 1);
            rebuilt.add(l.elems().get(0));
            rebuilt.addAll(body);
            return new SList(rebuilt);
        }
        String head = l.elems().get(0) instanceof SAtom a ? a.text() : "";
        List<SExpr> out = new ArrayList<>(l.elems().size());
        out.add(l.elems().get(0));
        for (int i = 1; i < l.elems().size(); i++) {
            boolean childStmt =
                    ("if".equals(head) && i >= 2 && (stmtPos || l.elems().size() == 3))
                            || ("while".equals(head) && i == 2)
                            || ("for".equals(head) && i == l.elems().size() - 1)
                            || ("for-range".equals(head) && i == l.elems().size() - 1);
            out.add(normalizeExpr(l.elems().get(i), childStmt));
        }
        return new SList(out);
    }

    private static List<SExpr> alphaNormalizeList(
            List<SExpr> stmts, Map<String, String> alpha, int[] nextIdx) {
        List<SExpr> out = new ArrayList<>(stmts.size());
        for (SExpr s : stmts) {
            out.add(alphaNormalizeExpr(s, alpha, nextIdx));
        }
        return out;
    }

    private static SExpr alphaNormalizeExpr(SExpr expr, Map<String, String> alpha, int[] nextIdx) {
        if (expr instanceof SAtom a) {
            String txt = a.text();
            Matcher m = VAR_TOKEN.matcher(txt);
            if (m.find()) {
                StringBuilder sb = new StringBuilder();
                int last = 0;
                do {
                    sb.append(txt, last, m.start());
                    String name = m.group(1);
                    String mapped = alpha.getOrDefault(name, name);
                    sb.append("var(").append(mapped).append(')');
                    last = m.end();
                } while (m.find());
                sb.append(txt, last, txt.length());
                return new SAtom(sb.toString());
            }
            return a;
        }
        SList l = (SList) expr;
        if (l.elems().isEmpty()) {
            return l;
        }
        String head = l.elems().get(0) instanceof SAtom a ? a.text() : "";
        if ("def".equals(head)
                && l.elems().size() >= 3
                && l.elems().get(1) instanceof SAtom nameAtom) {
            // Normalize RHS first so self-shadowing keeps outer binding
            List<SExpr> out = new ArrayList<>(l.elems().size());
            out.add(l.elems().get(0));
            String orig = nameAtom.text();
            SExpr normRhs = alphaNormalizeExpr(l.elems().get(2), alpha, nextIdx);
            String fresh = "$v" + (nextIdx[0]++);
            alpha.put(orig, fresh);
            out.add(new SAtom(fresh));
            out.add(normRhs);
            for (int i = 3; i < l.elems().size(); i++) {
                out.add(alphaNormalizeExpr(l.elems().get(i), alpha, nextIdx));
            }
            return new SList(out);
        }
        if ("set".equals(head)
                && l.elems().size() >= 3
                && l.elems().get(1) instanceof SAtom nameAtom) {
            List<SExpr> out = new ArrayList<>(l.elems().size());
            out.add(l.elems().get(0));
            String orig = nameAtom.text();
            out.add(new SAtom(alpha.getOrDefault(orig, orig)));
            for (int i = 2; i < l.elems().size(); i++) {
                out.add(alphaNormalizeExpr(l.elems().get(i), alpha, nextIdx));
            }
            return new SList(out);
        }
        List<SExpr> out = new ArrayList<>(l.elems().size());
        out.add(l.elems().get(0));
        for (int i = 1; i < l.elems().size(); i++) {
            out.add(alphaNormalizeExpr(l.elems().get(i), alpha, nextIdx));
        }
        return new SList(out);
    }
}
