package org.babblelang.tests;

import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.ir.IrObserver;
import org.babblelang.ir.IrSnapshot;
import org.babblelang.ir.IrTarget;
import org.babblelang.ir.IrTier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.SimpleScriptContext;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the unified {@code org.babblelang.ir} observation pipeline and
 * {@code HirCanonicalPrinter} ({@code BIP029-I1}, {@code BIP029-D5}–{@code D7}).
 */
public class HirCanonicalPrinterTestCase {

    private static Map<String, IrSnapshot> compileAndCapture(String source, int optLevel, String... stages) throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        List<String> wanted = List.of(stages);
        Map<String, IrSnapshot> captured = new LinkedHashMap<>();
        IrObserver observer = new IrObserver() {
            @Override
            public boolean wants(IrTier tier, int opt, String stage) {
                return tier == IrTier.HIR && wanted.contains(stage);
            }

            @Override
            public void onSnapshot(IrSnapshot snapshot) {
                captured.put(snapshot.stage(), snapshot);
            }
        };
        engine.put(BabbleCompiler.IR_OBSERVER, observer);
        SimpleScriptContext ctx = new SimpleScriptContext();
        ctx.setBindings(engine.createBindings(), SimpleScriptContext.ENGINE_SCOPE);
        ctx.setWriter(new StringWriter());
        ctx.setErrorWriter(new StringWriter());
        engine.eval(source, ctx);
        return captured;
    }

    @Test
    public void testIrTierTargetAndSnapshotValidation() {
        Assertions.assertEquals(IrTier.HIR, IrTier.parse("hir"));
        Assertions.assertEquals(IrTier.MIR, IrTier.parse("MIR"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> IrTier.parse("llvm"));

        Assertions.assertEquals(new IrTarget.Fn("norm"), IrTarget.parse("fn=norm"));
        Assertions.assertEquals("fn=norm", IrTarget.parse("fn=norm").format());
        Assertions.assertEquals(new IrTarget.Method("Vec3", "dot"), IrTarget.parse("fn=Vec3.dot"));
        Assertions.assertEquals("fn=Vec3.dot", IrTarget.parse("fn=Vec3.dot").format());
        Assertions.assertEquals(new IrTarget.TypeDef(IrTarget.TypeKind.RECORD, "Vec3"), IrTarget.parse("record=Vec3"));
        Assertions.assertEquals(new IrTarget.TypeDef(IrTarget.TypeKind.CLASS, "Box"), IrTarget.parse("class=Box"));
        Assertions.assertEquals(new IrTarget.TypeDef(IrTarget.TypeKind.INTERFACE, "Shape"), IrTarget.parse("interface=Shape"));
        Assertions.assertEquals(new IrTarget.TopLevel(), IrTarget.parse("top-level"));
        Assertions.assertEquals(new IrTarget.Module(), IrTarget.parse("module"));

        Assertions.assertThrows(IllegalArgumentException.class, () -> IrTarget.parse("fn="));
        Assertions.assertThrows(IllegalArgumentException.class, () -> IrTarget.parse("fn=A.B.C"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> IrTarget.parse("unknown=x"));

        Assertions.assertEquals("before", IrSnapshot.validateStage("before"));
        Assertions.assertEquals("final", IrSnapshot.validateStage("final"));
        Assertions.assertEquals("after:HirLicm", IrSnapshot.validateStage("after:HirLicm"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> IrSnapshot.validateStage("after:"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> IrSnapshot.validateStage("middle"));
    }

    @Test
    public void testPreambleIndependenceAndSyntheticTempRenumbering() throws Exception {
        String core = ""
                + "def sumScaled = fn (n: int, angle: double): double -> (\n"
                + "    var i = 0\n"
                + "    var acc = 0.0\n"
                + "    while i < n then (\n"
                + "        acc += java.lang.Math.cos(angle) * 2.0\n"
                + "        i += 1\n"
                + "    )\n"
                + "    acc\n"
                + ")\n"
                + "assert(sumScaled(2, 0.0) == 4.0)\n";

        String heavyPreamble = ""
                + "def p1 = 10\n"
                + "def p2 = 20\n"
                + "def helperA = fn (a: int, b: int): int -> ( def t = a + b ; t * 2 )\n"
                + "def helperB = fn (s: string): string -> ( s.trim() )\n"
                + "def Dummy = record (def x: double, def y: double) ()\n"
                + "assert(helperA(p1, p2) == 60)\n";

        IrTarget target = IrTarget.parse("fn=sumScaled");
        String withoutPreamble = compileAndCapture(core, 1, IrSnapshot.STAGE_FINAL)
                .get(IrSnapshot.STAGE_FINAL).ir().printCanonical(target);
        String withPreamble = compileAndCapture(heavyPreamble + core, 1, IrSnapshot.STAGE_FINAL)
                .get(IrSnapshot.STAGE_FINAL).ir().printCanonical(target);

        Assertions.assertEquals(withoutPreamble, withPreamble,
                "Canonical HIR must be byte-identical regardless of preamble definitions");
        Assertions.assertTrue(withoutPreamble.contains("$licm0"),
                "Hoisted LICM temp should be locally renumbered to $licm0:\n" + withoutPreamble);
        Assertions.assertFalse(withoutPreamble.contains("Script$"),
                "Generated Script$N prefix must not leak:\n" + withoutPreamble);
    }

    @Test
    public void testShadowedLocalsAndSymbolicCallReferences() throws Exception {
        String src = ""
                + "def bigClamp = fn axiom pure nothrow idempotent (x: int): int -> (\n"
                + "    def r = if x < 0 then (0) else (x)\n"
                + "    return r\n"
                + "    r\n"
                + ")\n"
                + "def norm = fn (x: int): int -> (\n"
                + "    bigClamp(bigClamp(java.lang.Math.abs(java.lang.Math.abs(x))))\n"
                + ")\n"
                + "def shadow = fn (x: int): int -> (\n"
                + "    def a = x + 1\n"
                + "    def b = (def a = x + 2 ; a)\n"
                + "    a + b\n"
                + ")\n"
                + "assert(norm(-5) == 5)\n"
                + "assert(shadow(3) == 9)\n";

        String normO0 = compileAndCapture(src, 0, IrSnapshot.STAGE_FINAL)
                .get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("fn=norm"));
        Map<String, IrSnapshot> normO1Snaps = compileAndCapture(src, 1,
                IrSnapshot.afterPass("HirEGraphSimplify"), IrSnapshot.STAGE_FINAL);
        String normO1Final = normO1Snaps.get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("fn=norm"));

        Assertions.assertTrue(normO0.contains("(call-fn bigClamp (call-fn bigClamp (jcall Math.abs (jcall Math.abs var(x)))))"),
                "Expected symbolic call-fn and double abs at -O0:\n" + normO0);
        // At -O1, HirEGraphSimplify collapses double bigClamp and double Math.abs, then InlinePass inlines bigClamp.
        int firstAbs = normO1Final.indexOf("Math.abs");
        Assertions.assertTrue(firstAbs >= 0 && normO1Final.indexOf("Math.abs", firstAbs + 1) < 0,
                "Expected single Math.abs after -O1:\n" + normO1Final);
        Assertions.assertFalse(normO1Final.contains("call-fn bigClamp"),
                "Expected bigClamp inlined at -O1:\n" + normO1Final);
        Assertions.assertNotEquals(normO0, normO1Final);

        String shadowO0 = compileAndCapture(src, 0, IrSnapshot.STAGE_FINAL)
                .get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("fn=shadow"));
        Assertions.assertTrue(shadowO0.contains("a#1") && shadowO0.contains("a#2"),
                "Shadowed locals must receive local #1/#2 disambiguation:\n" + shadowO0);
        Assertions.assertTrue(shadowO0.contains("var(x)"),
                "Unique local/param should omit #id:\n" + shadowO0);
    }

    @Test
    public void testRecordMethodInterfaceAndStageAfterPassSnapshots() throws Exception {
        String src = ""
                + "def Greeter = interface (\n"
                + "    def hi = fn (): string -> ( \"hi\" )\n"
                + ")\n"
                + "def Vec2 = record (def x: double, def y: double) (\n"
                + "    def dot = fn (other: Vec2): double -> ( this.x * other.x + this.y * other.y )\n"
                + ")\n"
                + "def useVec = fn (a: double, b: double): double -> (\n"
                + "    def v = Vec2(a, b)\n"
                + "    v.dot(v)\n"
                + ")\n"
                + "assert(useVec(3.0, 4.0) == 25.0)\n";

        Map<String, IrSnapshot> snaps = compileAndCapture(src, 1,
                IrSnapshot.STAGE_BEFORE,
                IrSnapshot.afterPass("HirEGraphSimplify"),
                IrSnapshot.afterPass("ScalarReplace"),
                IrSnapshot.STAGE_FINAL);

        Assertions.assertTrue(snaps.containsKey(IrSnapshot.STAGE_BEFORE));
        Assertions.assertTrue(snaps.containsKey("after:HirEGraphSimplify"));
        Assertions.assertTrue(snaps.containsKey("after:ScalarReplace"));
        Assertions.assertTrue(snaps.containsKey(IrSnapshot.STAGE_FINAL));

        String rec = snaps.get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("record=Vec2"));
        Assertions.assertTrue(rec.startsWith("record Vec2"), rec);
        Assertions.assertTrue(rec.contains("fn Vec2.dot"), "record target must include owned methods:\n" + rec);

        String dot = snaps.get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("fn=Vec2.dot"));
        Assertions.assertTrue(dot.startsWith("fn Vec2.dot"), dot);

        String iface = snaps.get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("interface=Greeter"));
        Assertions.assertTrue(iface.startsWith("interface Greeter"), iface);

        String beforeUse = snaps.get(IrSnapshot.STAGE_BEFORE).ir().printCanonical(IrTarget.parse("fn=useVec"));
        String finalUse = snaps.get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("fn=useVec"));
        Assertions.assertTrue(beforeUse.contains("record-new Vec2"), beforeUse);
        Assertions.assertFalse(finalUse.contains("record-new"), "ScalarReplace should eliminate Vec2 allocation:\n" + finalUse);
    }

    @Test
    public void testCombineObserverAndMissingTargetErrors() throws Exception {
        List<String> events = new ArrayList<>();
        IrObserver a = new IrObserver() {
            @Override
            public boolean wants(IrTier tier, int optLevel, String stage) {
                return IrSnapshot.STAGE_BEFORE.equals(stage);
            }

            @Override
            public void onSnapshot(IrSnapshot snapshot) {
                events.add("a:" + snapshot.stage());
            }
        };
        IrObserver b = new IrObserver() {
            @Override
            public boolean wants(IrTier tier, int optLevel, String stage) {
                return IrSnapshot.STAGE_FINAL.equals(stage);
            }

            @Override
            public void onSnapshot(IrSnapshot snapshot) {
                events.add("b:" + snapshot.stage());
            }
        };
        IrObserver combined = IrObserver.combine(a, b);
        Assertions.assertTrue(combined.wants(IrTier.HIR, 1, IrSnapshot.STAGE_BEFORE));
        Assertions.assertTrue(combined.wants(IrTier.HIR, 1, IrSnapshot.STAGE_FINAL));
        Assertions.assertFalse(combined.wants(IrTier.HIR, 1, "after:Inline"));

        Map<String, IrSnapshot> snaps = compileAndCapture("def f = fn (): int -> ( 1 )\nassert(f() == 1)\n",
                1, IrSnapshot.STAGE_FINAL);
        var ir = snaps.get(IrSnapshot.STAGE_FINAL).ir();
        Assertions.assertThrows(IllegalArgumentException.class, () -> ir.printCanonical(IrTarget.parse("fn=missing")));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ir.printCanonical(IrTarget.parse("record=Missing")));
        Assertions.assertTrue(ir.printCanonical(IrTarget.parse("top-level")).startsWith("top-level:"));
        Assertions.assertTrue(ir.printCanonical(IrTarget.parse("module")).startsWith("module <main>"));
    }

    @Test
    public void testBabbleTypeDescriptorsStripGlobalTypeIds() throws Exception {
        String src = ""
                + "def ErrA = class (def code: int) ()\n"
                + "def run = fn (x: any): int -> (\n"
                + "    try (\n"
                + "        if x is ErrA then ( (x as ErrA).code ) else ( throw ErrA(7) )\n"
                + "    ) catch e: ErrA then ( e.code )\n"
                + ")\n"
                + "assert(run(ErrA(3)) == 3 and run(0) == 7)\n";

        String first = compileAndCapture(src, 1, IrSnapshot.STAGE_FINAL)
                .get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("fn=run"));
        String second = compileAndCapture(src, 1, IrSnapshot.STAGE_FINAL)
                .get(IrSnapshot.STAGE_FINAL).ir().printCanonical(IrTarget.parse("fn=run"));

        Assertions.assertEquals(first, second, "Canonical HIR must be deterministic across compilations");
        Assertions.assertFalse(first.matches("(?s).*\\|T\\d+.*"),
                "JVM-global |T<N> type IDs must not leak into canonical HIR:\n" + first);
        Assertions.assertTrue(first.contains("babble|ErrA"),
                "Expected normalized babble|ErrA descriptor:\n" + first);
    }
}
