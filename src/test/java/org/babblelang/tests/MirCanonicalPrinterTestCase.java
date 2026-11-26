package org.babblelang.tests;

import java.io.StringWriter;
import java.util.List;
import javax.script.SimpleScriptContext;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.ir.CliIrDumper;
import org.babblelang.ir.IrObserver;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTarget;
import org.babblelang.ir.IrTier;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirLowerer;
import org.babblelang.ir.mir.MirModule;
import org.babblelang.ir.mir.MirVerifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SSA {@code MIR} lowering, {@link MirCfg}, {@link MirVerifier}, {@link
 * org.babblelang.ir.mir.MirCanonicalPrinter}, and {@link CliIrDumper} MIR specs ({@code BIP014-I3},
 * {@code BIP029}).
 */
public class MirCanonicalPrinterTestCase {

    private static MirModule lowerFromSource(String source, int optLevel) throws Exception {
        BabbleScriptEngine engine =
                (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        HirModule[] holder = new HirModule[1];
        engine.put(
                BabbleCompiler.IR_OBSERVER,
                new IrObserver() {
                    @Override
                    public boolean wants(IrTier tier, int opt, String stage) {
                        return tier == IrTier.HIR
                                && (opt == 0
                                        ? IrSnapshot.STAGE_BEFORE.equals(stage)
                                        : IrSnapshot.STAGE_FINAL.equals(stage));
                    }

                    @Override
                    public void onSnapshot(IrSnapshot snapshot) {
                        if (snapshot.ir() instanceof HirModule hm) {
                            holder[0] = hm;
                        }
                    }
                });
        SimpleScriptContext ctx = new SimpleScriptContext();
        ctx.setBindings(engine.createBindings(), SimpleScriptContext.ENGINE_SCOPE);
        ctx.setWriter(new StringWriter());
        ctx.setErrorWriter(new StringWriter());
        engine.eval(source, ctx);
        Assertions.assertNotNull(holder[0], "Expected HIR snapshot");
        MirModule mir = MirLowerer.lower(holder[0]);
        MirVerifier.verify(mir);
        return mir;
    }

    @Test
    public void testPreambleIndependenceAndDeterministicSsaNumbering() throws Exception {
        String core =
                ""
                        + "def sumTo = fn (n: int): int -> (\n"
                        + "    var acc = 0\n"
                        + "    var i = 0\n"
                        + "    while i < n then (\n"
                        + "        acc += i\n"
                        + "        i += 1\n"
                        + "    )\n"
                        + "    acc\n"
                        + ")\n"
                        + "assert(sumTo(5) == 10)\n";

        String preamble =
                ""
                        + "def p1 = 42\n"
                        + "def helper = fn (a: int, b: int): int -> ( a * b + 1 )\n"
                        + "assert(helper(p1, 2) == 85)\n";

        MirModule m1 = lowerFromSource(core, 1);
        MirModule m2 = lowerFromSource(preamble + core, 1);
        IrTarget target = IrTarget.parse("fn=sumTo");
        String d1 = m1.printCanonical(target);
        String d2 = m2.printCanonical(target);

        Assertions.assertEquals(d1, d2, "Canonical MIR must be preamble-independent");
        Assertions.assertTrue(d1.startsWith("fn sumTo(v0: int) -> int:"), d1);
        Assertions.assertTrue(d1.contains("bb0:"), d1);
        Assertions.assertTrue(d1.contains("br_if "), d1);
        Assertions.assertFalse(d1.contains("Script$"), d1);
    }

    @Test
    public void testTryCatchFinallyLoweringAndVerifier() throws Exception {
        String src =
                ""
                        + "def AppErr = record (def msg: string) ()\n"
                        + "def safeDiv = fn (x: int, flag: bool): int -> (\n"
                        + "    var out = 0\n"
                        + "    try (\n"
                        + "        out = x + 1\n"
                        + "        if flag then ( throw AppErr(\"boom\") )\n"
                        + "        out = x + 2\n"
                        + "    ) catch e: AppErr then (\n"
                        + "        out = -1\n"
                        + "    ) finally (\n"
                        + "        out = out + 10\n"
                        + "    )\n"
                        + "    out\n"
                        + ")\n"
                        + "assert(safeDiv(5, false) == 17)\n"
                        + "assert(safeDiv(5, true) == 9)\n";

        MirModule mir = lowerFromSource(src, 1);
        String dump = mir.printCanonical(IrTarget.parse("fn=safeDiv"));
        Assertions.assertTrue(dump.contains("[unwind:"), "Expected unwind handlers:\n" + dump);
        Assertions.assertTrue(
                dump.contains("[handler]:"), "Expected handler-entry blocks:\n" + dump);
        Assertions.assertTrue(
                dump.contains("unwrap_thrown"), "Expected ThrownException unwrap:\n" + dump);
        Assertions.assertTrue(
                dump.contains("slot_write out"),
                "Outer try-mutated local must be pinned:\n" + dump);
    }

    @Test
    public void testNarrowingAnchorOnNullCheckAndIsCheck() throws Exception {
        String src =
                ""
                        + "def lenOrZero = fn (s: string?): int -> (\n"
                        + "    if s <> null then ( s.length() ) else ( 0 )\n"
                        + ")\n"
                        + "assert(lenOrZero(\"hi\") == 2)\n"
                        + "assert(lenOrZero(null) == 0)\n";

        MirModule mir = lowerFromSource(src, 1);
        String dump = mir.printCanonical(IrTarget.parse("fn=lenOrZero"));
        Assertions.assertTrue(
                dump.contains("narrow.nonnull"),
                "Expected Narrow anchor in guarded branch:\n" + dump);
    }

    @Test
    public void testRecordMethodAndNaturalLoopDetection() throws Exception {
        String src =
                ""
                        + "def Counter = record (def step: int) (\n"
                        + "    def run = fn (n: int): int -> (\n"
                        + "        var i = 0\n"
                        + "        var s = 0\n"
                        + "        while i < n then (\n"
                        + "            s += this.step\n"
                        + "            i += 1\n"
                        + "        )\n"
                        + "        s\n"
                        + "    )\n"
                        + ")\n"
                        + "assert(Counter(3).run(4) == 12)\n";

        MirModule mir = lowerFromSource(src, 1);
        String methodDump = mir.printCanonical(IrTarget.parse("fn=Counter.run"));
        Assertions.assertTrue(methodDump.contains("fn Counter.run("), methodDump);
        Assertions.assertTrue(
                methodDump.contains("load_field ") && methodDump.contains("{read:frozen}"),
                methodDump);

        MirFunction fn =
                mir.functions().values().stream()
                        .filter(f -> "run".equals(f.name()))
                        .findFirst()
                        .orElseThrow();
        MirCfg cfg = MirCfg.analyze(fn);
        List<MirCfg.NaturalLoop> loops = cfg.findNaturalLoops();
        Assertions.assertEquals(1, loops.size(), "Expected 1 natural loop in Counter.run");
        Assertions.assertFalse(loops.get(0).outsidePredecessors().isEmpty());
        for (MirBlock b : cfg.rpo()) {
            Assertions.assertTrue(cfg.dominates(fn.entryBlockId(), b.id()));
        }
    }

    @Test
    public void testTopLevelAndRawPrintAndCliIrDumperMirSpecs() throws Exception {
        String src = "def f = fn (x: int): int -> ( x + 1 )\nassert(f(2) == 3)\n";
        MirModule mir = lowerFromSource(src, 1);
        Assertions.assertEquals(
                "top-level ; [not-lowered: top-level]",
                mir.printCanonical(IrTarget.parse("top-level")));
        String raw = mir.printRaw();
        Assertions.assertTrue(raw.contains("mir module"), raw);

        IrObserver mirBefore = CliIrDumper.forSpec("mir:before:canonical", "Script$0", 2);
        Assertions.assertNotNull(mirBefore);
        Assertions.assertTrue(mirBefore.wants(IrTier.MIR, 2, IrSnapshot.STAGE_BEFORE));
        Assertions.assertFalse(mirBefore.wants(IrTier.MIR, 2, IrSnapshot.STAGE_FINAL));
        Assertions.assertFalse(mirBefore.wants(IrTier.HIR, 2, IrSnapshot.STAGE_BEFORE));

        IrObserver mirAfter = CliIrDumper.forSpec("mir:after:SccpPass:canonical", "Script$0", 2);
        Assertions.assertNotNull(mirAfter);
        Assertions.assertTrue(mirAfter.wants(IrTier.MIR, 2, "after:SccpPass"));
        Assertions.assertFalse(mirAfter.wants(IrTier.MIR, 2, IrSnapshot.STAGE_BEFORE));
    }

    @Test
    public void testNamedOpaqueExprsAndNameReadEffects() throws Exception {
        String src =
                ""
                        + "def Pair = record (def a: int, def b: int) ()\n"
                        + "var counter = 1\n"
                        + "def useBoth = fn volatile (n: int): Pair -> (\n"
                        + "    var p = Pair(n, counter)\n"
                        + "    var i = 0\n"
                        + "    while i < n then (\n"
                        + "        p = Pair(p.a + 1, p.b)\n"
                        + "        i += 1\n"
                        + "    )\n"
                        + "    p\n"
                        + ")\n"
                        + "def bump = fn io (): int -> ( counter = counter + 1 ; counter )\n"
                        + "assert(bump() == 2)\n"
                        + "def lateConst = 7\n"
                        + "def readLate = fn (): int -> ( lateConst )\n"
                        + "assert(useBoth(3).a == 6 and readLate() == 7)\n";

        MirModule before = lowerFromSource(src, 1);
        String useBothBefore = before.printCanonical(IrTarget.parse("fn=useBoth"));
        Assertions.assertTrue(useBothBefore.contains("name_read Pair {pure}"), useBothBefore);
        Assertions.assertTrue(
                useBothBefore.contains("name_read counter {read:shared}"), useBothBefore);
        Assertions.assertTrue(useBothBefore.contains("record_new Pair["), useBothBefore);

        String bumpBefore = before.printCanonical(IrTarget.parse("fn=bump"));
        Assertions.assertTrue(bumpBefore.contains("name_assign counter,"), bumpBefore);

        String lateBefore = before.printCanonical(IrTarget.parse("fn=readLate"));
        Assertions.assertTrue(lateBefore.contains("name_read lateConst {pure,throws}"), lateBefore);

        // Optimize at -O2: GVN + LICM should hoist and deduplicate the two Pair reads into one in
        // bb0.
        MirModule after =
                org.babblelang.opt.mir.MirPassManager.defaultFor(2)
                        .optimize(
                                before,
                                org.babblelang.opt.PassContext.of(2, true, null),
                                null,
                                "test");
        String useBothAfter = after.printCanonical(IrTarget.parse("fn=useBoth"));
        int pairReads = useBothAfter.split("name_read Pair", -1).length - 1;
        Assertions.assertEquals(
                1,
                pairReads,
                "Expected GVN/LICM to leave a single name_read Pair:\n" + useBothAfter);

        // A pure non-escaping static block keeps the inductive header open (BIP034-D6), so
        // afterStatic is {pure}, whereas an active static block calling a function stops the
        // header so afterActive remains {pure,throws}.
        String pureStaticSrc =
                ""
                        + "def f = fn (): int -> ( afterStatic )\n"
                        + "def WithStatic = class () () static ( def x = 1 )\n"
                        + "def afterStatic = 42\n"
                        + "assert(f() == 42 and WithStatic.x == 1)\n";
        MirModule pureStaticMir = lowerFromSource(pureStaticSrc, 1);
        String fPureDump = pureStaticMir.printCanonical(IrTarget.parse("fn=f"));
        Assertions.assertTrue(fPureDump.contains("name_read afterStatic {pure}"), fPureDump);

        String activeStaticSrc =
                ""
                        + "def ping = fn io (): int -> ( 1 )\n"
                        + "def f = fn (): int -> ( afterActive )\n"
                        + "def WithActive = class () () static ( def x = ping() )\n"
                        + "def afterActive = 42\n"
                        + "assert(f() == 42 and WithActive.x == 1)\n";
        MirModule activeStaticMir = lowerFromSource(activeStaticSrc, 1);
        String fActiveDump = activeStaticMir.printCanonical(IrTarget.parse("fn=f"));
        Assertions.assertTrue(
                fActiveDump.contains("name_read afterActive {pure,throws}"), fActiveDump);
    }
}
