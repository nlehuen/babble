package org.babblelang.tests;

import org.babblelang.wasm.WasmInstr;
import org.babblelang.wasm.WasmModule;
import org.babblelang.wasm.WasmTypeDef;
import org.babblelang.wasm.WasmValType;
import org.babblelang.wasm.WasmVerifier;
import org.babblelang.wasm.WasmWriter;
import org.babblelang.wasm.WatPrinter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@code BIP025-I0} pure-Java WebAssembly 3.0 binary emission ({@link WasmWriter}),
 * text printing ({@link WatPrinter}), structural verification ({@link WasmVerifier}), and real
 * Node.js (V8) execution of WasmGC structs/arrays, typed function refs, tail calls, and
 * {@code try_table}/{@code exnref} exceptions.
 */
public class WasmWriterTestCase {

    @Test
    public void wasmGcStructSubtypingAndRefTestCast() {
        WasmModule mod = new WasmModule("gc_test");
        int baseIdx = mod.addStructType("Base", false, -1, List.of(
                WasmTypeDef.Field.imm("tag", WasmValType.I32)
        ));
        int pointIdx = mod.addStructType("Point", true, baseIdx, List.of(
                WasmTypeDef.Field.imm("tag", WasmValType.I32),
                WasmTypeDef.Field.imm("x", WasmValType.F64),
                WasmTypeDef.Field.imm("y", WasmValType.F64)
        ));
        int fnType = mod.addFuncType("dotSq", List.of(WasmValType.F64, WasmValType.F64), List.of(WasmValType.F64));
        WasmModule.FuncDef fn = mod.addFunction("dotSq", fnType, List.of("x", "y"));
        int baseLocal = fn.addLocal("b", WasmValType.refNull(baseIdx), 2);
        var c = fn.code();
        // Construct Point(1, x, y), upcast to Base, ref.test Point, ref.cast Point, return x*x + y*y
        c.i32Const(1).localGet(0).localGet(1).structNew(pointIdx).localSet(baseLocal);
        c.localGet(baseLocal).refTest(pointIdx).if_(WasmValType.F64);
        c.localGet(baseLocal).refCast(pointIdx).structGet(pointIdx, 1);
        c.localGet(baseLocal).refCast(pointIdx).structGet(pointIdx, 1).op(WasmInstr.SimpleOp.F64_MUL);
        c.localGet(baseLocal).refCast(pointIdx).structGet(pointIdx, 2);
        c.localGet(baseLocal).refCast(pointIdx).structGet(pointIdx, 2).op(WasmInstr.SimpleOp.F64_MUL);
        c.op(WasmInstr.SimpleOp.F64_ADD);
        c.else_().f64Const(-1.0).end();
        mod.addExport("dotSq", WasmModule.ExportKind.FUNC, fn.funcIndex());

        WasmVerifier.verify(mod);
        String wat = WatPrinter.print(mod);
        Assertions.assertTrue(wat.contains("(type $Point (sub final $Base"), wat);
        byte[] wasm = WasmWriter.encode(mod, true);
        Assertions.assertTrue(wasm.length > 8);

        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable());
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runBare(wasm, "dotSq", List.of(3.0, 4.0));
        Assertions.assertTrue(out.ok(), "Node error : " + out.error());
        Assertions.assertEquals("25", out.resultJson());
    }

    @Test
    public void wasmTailCallDeepRecursion() {
        WasmModule mod = new WasmModule("tail_call_test");
        int fnType = mod.addFuncType("sumTail", List.of(WasmValType.I32, WasmValType.I32), List.of(WasmValType.I32));
        WasmModule.FuncDef fn = mod.addFunction("sumTail", fnType, List.of("n", "acc"));
        var c = fn.code();
        // if n <= 0 return acc; else return_call sumTail(n - 1, acc + n)
        c.localGet(0).i32Const(0).op(WasmInstr.SimpleOp.I32_LE_S).if_();
        c.localGet(1).return_();
        c.end();
        c.localGet(0).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB);
        c.localGet(1).localGet(0).op(WasmInstr.SimpleOp.I32_ADD);
        c.returnCall(fn.funcIndex());
        mod.addExport("sumTail", WasmModule.ExportKind.FUNC, fn.funcIndex());

        WasmVerifier.verify(mod);
        byte[] wasm = WasmWriter.encode(mod);

        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable());
        // 500_000 iterations would overflow JS/Wasm call stacks without real return_call TCO!
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runBare(wasm, "sumTail", List.of(500_000, 0));
        Assertions.assertTrue(out.ok(), "Node error : " + out.error());
        // sum(1..500000) mod 2^32 as signed i32
        int expected = (int) (500_000L * 500_001L / 2L);
        Assertions.assertEquals(String.valueOf(expected), out.resultJson());
    }

    @Test
    public void wasmTryTableCatchRefAndThrowRef() {
        WasmModule mod = new WasmModule("exn_test");
        int tagSig = mod.addFuncType("exnSig", List.of(WasmValType.I32), List.of());
        int exnTag = mod.addTag("babble_exn", tagSig);

        int throwerSig = mod.addFuncType("throwerSig", List.of(WasmValType.I32), List.of(WasmValType.I32));
        WasmModule.FuncDef thrower = mod.addFunction("thrower", throwerSig);
        thrower.code().localGet(0).throw_(exnTag);

        // Block type for catch_ref: () -> (i32, (ref exn))
        int catchRefBlockType = mod.addFuncType("catchRefRes", List.of(), List.of(WasmValType.I32, WasmValType.NON_NULL_EXN));
        int mainSig = mod.addFuncType("mainSig", List.of(WasmValType.I32), List.of(WasmValType.I32));
        WasmModule.FuncDef main = mod.addFunction("run", mainSig);
        int valLocal = main.addLocal("v", WasmValType.I32, 1);
        int exnLocal = main.addLocal("e", WasmValType.EXNREF, 1);

        var c = main.code();
        // Outer catch that receives rethrown exn
        c.block(WasmValType.I32); // $outer (result i32)
        c.tryTable(WasmValType.I32, List.of(new WasmInstr.CatchClause(WasmInstr.CatchKind.CATCH, exnTag, 0)));
        // Inner catch_ref that inspects payload and rethrows via throw_ref if payload == 99
        c.blockType(catchRefBlockType); // $inner_catch -> (i32, ref exn)
        c.tryTable(WasmValType.I32, List.of(new WasmInstr.CatchClause(WasmInstr.CatchKind.CATCH_REF, exnTag, 0)));
        c.localGet(0).call(thrower.funcIndex());
        c.end(); // end inner try_table
        c.return_();
        c.end(); // end $inner_catch -> stack: [i32, ref exn]
        c.localSet(exnLocal);
        c.localSet(valLocal);
        c.localGet(valLocal).i32Const(99).op(WasmInstr.SimpleOp.I32_EQ).if_();
        c.localGet(exnLocal).refAsNonNull().throwRef();
        c.end();
        c.localGet(valLocal).i32Const(1000).op(WasmInstr.SimpleOp.I32_ADD);
        c.end(); // end outer try_table
        c.return_();
        c.end(); // end $outer -> caught rethrown payload on stack
        c.i32Const(2000).op(WasmInstr.SimpleOp.I32_ADD);

        mod.addExport("run", WasmModule.ExportKind.FUNC, main.funcIndex());

        WasmVerifier.verify(mod);
        byte[] wasm = WasmWriter.encode(mod);

        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable());
        NodeWasmRunner.RunOutcome r1 = NodeWasmRunner.runBare(wasm, "run", List.of(42));
        Assertions.assertTrue(r1.ok(), "Node error : " + r1.error());
        Assertions.assertEquals("1042", r1.resultJson());

        NodeWasmRunner.RunOutcome r2 = NodeWasmRunner.runBare(wasm, "run", List.of(99));
        Assertions.assertTrue(r2.ok(), "Node error : " + r2.error());
        Assertions.assertEquals("2099", r2.resultJson());
    }

    @Test
    public void wasmArrayNewDataAndCallRef() {
        WasmModule mod = new WasmModule("arr_and_callref");
        int i16Arr = mod.addArrayType("CharArr", WasmValType.I16, false);
        int dataIdx = mod.addPassiveData(new byte[]{'H', 0, 'i', 0}); // UTF-16LE "Hi"
        int cbType = mod.addFuncType("cb", List.of(WasmValType.I32), List.of(WasmValType.I32));
        WasmModule.FuncDef dbl = mod.addFunction("dbl", cbType);
        dbl.code().localGet(0).i32Const(2).op(WasmInstr.SimpleOp.I32_MUL);

        int mainType = mod.addFuncType("main", List.of(), List.of(WasmValType.I32));
        WasmModule.FuncDef main = mod.addFunction("main", mainType);
        int arrLocal = main.addLocal("a", WasmValType.refNull(i16Arr), 0);
        var c = main.code();
        c.i32Const(0).i32Const(2).arrayNewData(i16Arr, dataIdx).localSet(arrLocal);
        // Load 'H' (72) and pass to call_ref dbl -> 144
        c.localGet(arrLocal).i32Const(0).arrayGetU(i16Arr);
        c.refFunc(dbl.funcIndex()).callRef(cbType);
        mod.addExport("main", WasmModule.ExportKind.FUNC, main.funcIndex());

        WasmVerifier.verify(mod);
        byte[] wasm = WasmWriter.encode(mod);

        Assumptions.assumeTrue(NodeWasmRunner.isNodeAvailable());
        NodeWasmRunner.RunOutcome out = NodeWasmRunner.runBare(wasm, "main", List.of());
        Assertions.assertTrue(out.ok(), "Node error : " + out.error());
        Assertions.assertEquals("144", out.resultJson());
    }

    @Test
    public void verifierRejectsStructSetOnImmutableRecordField() {
        WasmModule mod = new WasmModule("imm_fail");
        int vec = mod.addStructType("Vec3", true, -1, List.of(WasmTypeDef.Field.imm("x", WasmValType.F64)));
        int fnType = mod.addFuncType("bad", List.of(WasmValType.ref(vec)), List.of());
        WasmModule.FuncDef fn = mod.addFunction("bad", fnType);
        fn.code().localGet(0).f64Const(1.0).structSet(vec, 0);

        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class, () -> WasmVerifier.verify(mod));
        Assertions.assertTrue(ex.getMessage().contains("immutable"), ex.getMessage());
    }

    @Test
    public void compilationTargetParsingAndDeferredWasiMessage() {
        Assertions.assertEquals(org.babblelang.analysis.CompilationTarget.JVM,
                org.babblelang.analysis.CompilationTarget.parse("jvm"));
        Assertions.assertEquals(org.babblelang.analysis.CompilationTarget.WASM_JS,
                org.babblelang.analysis.CompilationTarget.parse("wasm-js"));
        Assertions.assertEquals(org.babblelang.analysis.CompilationTarget.WASM_BARE,
                org.babblelang.analysis.CompilationTarget.parse("wasm-bare"));
        IllegalArgumentException wasi = Assertions.assertThrows(IllegalArgumentException.class,
                () -> org.babblelang.analysis.CompilationTarget.parse("wasm-wasi"));
        Assertions.assertTrue(wasi.getMessage().contains("deferred"), wasi.getMessage());
    }

    @Test
    public void portabilityCheckerRejectsJavaAndAllowsRaytracerArgon() throws Exception {
        var engine = (org.babblelang.engine.BabbleScriptEngine)
                org.babblelang.engine.BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        // Non-portable Java usage rejected
        var badMods = engine.compileSourceToHir(
                "def f = fn io () -> (java.io.File(\"x\"))\nf()",
                "bad.ba", org.babblelang.analysis.CompilationTarget.WASM_JS);
        var badDiags = org.babblelang.wasm.WasmPortabilityChecker.check(
                badMods.stream().map(org.babblelang.engine.BabbleScriptEngine.HirProgramModule::hir).toList(),
                org.babblelang.analysis.CompilationTarget.WASM_JS);
        Assertions.assertFalse(badDiags.isEmpty(), "Expected portability error for java.io.File");
        Assertions.assertTrue(badDiags.get(0).format().contains("Not supported on target wasm"),
                badDiags.get(0).format());

        // wasm-bare rejects println
        var bareMods = engine.compileSourceToHir(
                "println(42)",
                "bare.ba", org.babblelang.analysis.CompilationTarget.WASM_BARE);
        var bareDiags = org.babblelang.wasm.WasmPortabilityChecker.check(
                bareMods.stream().map(org.babblelang.engine.BabbleScriptEngine.HirProgramModule::hir).toList(),
                org.babblelang.analysis.CompilationTarget.WASM_BARE);
        Assertions.assertFalse(bareDiags.isEmpty(), "Expected wasm-bare to reject println");

        // All 15 modules of examples/raytracer-argon must be 100% portable under wasm-js!
        java.nio.file.Path argonRoot = java.nio.file.Path.of("examples/raytracer-argon");
        var argonMods = engine.compileProgramToHir(
                org.babblelang.engine.FileModuleSource.rooted(argonRoot),
                argonRoot.resolve("raytracer.ba").toAbsolutePath().normalize().toString(),
                org.babblelang.analysis.CompilationTarget.WASM_JS);
        Assertions.assertEquals(15, argonMods.size());
        var argonDiags = org.babblelang.wasm.WasmPortabilityChecker.check(
                argonMods.stream().map(org.babblelang.engine.BabbleScriptEngine.HirProgramModule::hir).toList(),
                org.babblelang.analysis.CompilationTarget.WASM_JS);
        Assertions.assertEquals(List.of(), argonDiags, "Argon portability errors : " + argonDiags);
    }

    @Test
    public void fbipReuseSkipsRecordReuseOnWasmTargets() throws Exception {
        String code = """
                export def Box = record (n: int, arr: array of int) ()
                export def inc = fn (unique b: Box): Box -> (
                    Box(b.n + 1, b.arr)
                )
                inc(Box(1, (array of int)(1)))
                """;
        var engine = (org.babblelang.engine.BabbleScriptEngine)
                org.babblelang.engine.BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(org.babblelang.compiler.BabbleCompiler.OPT_LEVEL, 1);

        var jvmHir = engine.compileSourceToHir(code, "reuse.ba", org.babblelang.analysis.CompilationTarget.JVM).get(0).hir();
        var wasmHir = engine.compileSourceToHir(code, "reuse.ba", org.babblelang.analysis.CompilationTarget.WASM_BARE).get(0).hir();

        String jvmPrinted = org.babblelang.ir.hir.HirPrinter.print(jvmHir);
        String wasmPrinted = org.babblelang.ir.hir.HirPrinter.print(wasmHir);
        Assertions.assertTrue(jvmPrinted.contains("record-reuse"), "Expected JVM -O1 to emit RecordReuse : " + jvmPrinted);
        Assertions.assertFalse(wasmPrinted.contains("record-reuse"), "Expected Wasm -O1 to skip RecordReuse (BIP025-D11) : " + wasmPrinted);
    }

    @Test
    public void batestV2ParsesWatBlocks() {
        String md = """
                ## Wasm wat check
                ```babble
                def f = fn (x: int): int -> (x + 1)
                assert(f(2) == 3)
                ```
                ```wat
                ;; wat -O1 fn=f check
                contains i32.add
                ```
                """;
        var file = org.babblelang.tests.batest.BatestParser.parseString(md, "wasm_sample.batest.md");
        Assertions.assertEquals(1, file.cases().size());
        Assertions.assertEquals(1, file.cases().get(0).irExpectations().size());
        Assertions.assertEquals(org.babblelang.ir.IrTier.WAT, file.cases().get(0).irExpectations().get(0).tier());
    }
}
