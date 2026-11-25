package org.babblelang.analysis;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.MapModuleSource;
import org.babblelang.engine.ModuleId;
import org.babblelang.engine.ModuleText;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit tests for RM003 Phase 1 analysis metadata: {@link LoweringTargets.TypeHierarchyInfo},
 * {@link LoweringTargets.SuperTargetInfo}, {@link LoweringTargets.IsTarget}, and
 * {@link LoweringTargets.InterfaceCallTarget}.
 */
public class Rm003P1AnalysisTestCase {

    private static ScopeAnalysis analyzeSingle(String source) throws Exception {
        BabbleParser.FileContext file = BabbleScriptEngine.parseSource(source, "test.ba");
        return BlockScopes.analyze(file);
    }

    private static ScopeAnalysis analyzeWithModules(MapModuleSource source, String mainKey) throws Exception {
        Map<String, ModuleInterface> cache = new HashMap<>();
        return analyzeModule(source, mainKey, cache);
    }

    private static ScopeAnalysis analyzeModule(MapModuleSource source, String key, Map<String, ModuleInterface> cache) throws Exception {
        ModuleId id = source.identify(key);
        ModuleText text = source.read(id);
        BabbleParser.FileContext file = BabbleScriptEngine.parseSource(text.text(), id.displayName());
        ModuleLoader loader = new ModuleLoader() {
            @Override
            public String moduleKey() {
                return id.key();
            }

            @Override
            public void started(ModuleInterface moduleInterface) {
                moduleInterface.setName(id.displayName());
                cache.put(id.key(), moduleInterface);
            }

            @Override
            public ModuleInterface load(String name, int line) {
                ModuleId target = source.resolve(name, id);
                ModuleInterface existing = cache.get(target.key());
                if (existing != null) {
                    return existing;
                }
                try {
                    return analyzeModule(source, target.key(), cache).moduleInterface();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        ScopeAnalysis analysis = BlockScopes.analyze(file, null, loader);
        Assertions.assertEquals(List.of(), analysis.typeDiagnostics(), analysis.typeDiagnostics().toString());
        return analysis;
    }

    private static List<BabbleParser.IsExpressionContext> isExpressions(BabbleParser.FileContext file) {
        List<BabbleParser.IsExpressionContext> result = new ArrayList<>();
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitIsExpression(BabbleParser.IsExpressionContext ctx) {
                result.add(ctx);
                return super.visitIsExpression(ctx);
            }
        }.visit(file);
        return result;
    }

    private static List<BabbleParser.CallContext> calls(BabbleParser.FileContext file) {
        List<BabbleParser.CallContext> result = new ArrayList<>();
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitCall(BabbleParser.CallContext ctx) {
                result.add(ctx);
                return super.visitCall(ctx);
            }
        }.visit(file);
        return result;
    }

    private static List<BabbleParser.SuperExpressionContext> supers(BabbleParser.FileContext file) {
        List<BabbleParser.SuperExpressionContext> result = new ArrayList<>();
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitSuperExpression(BabbleParser.SuperExpressionContext ctx) {
                result.add(ctx);
                return super.visitSuperExpression(ctx);
            }
        }.visit(file);
        return result;
    }

    @Test
    public void testTypeHierarchyInfoAndMemberSpecs() throws Exception {
        StringBuilder hugeParams = new StringBuilder();
        for (int i = 0; i < 205; i++) {
            if (i > 0) {
                hugeParams.append(", ");
            }
            hugeParams.append("p").append(i).append(" : int");
        }
        String source = ""
                + "def Base = interface (\n"
                + "  def req : fn (x : int) : int\n"
                + "  def prop : string\n"
                + "  def defMethod = fn () : int -> ( 10 )\n"
                + ")\n"
                + "def Box of T = interface (\n"
                + "  def get : fn () : T\n"
                + "  def put : fn (v : T) : null\n"
                + ")\n"
                + "def Huge = interface (\n"
                + "  def big : fn (" + hugeParams + ") : int\n"
                + ")\n"
                + "def Sub = interface extends Base, java.lang.Runnable (\n"
                + "  def subDef = fn (d : double) : double -> ( d + 1.0 )\n"
                + ")\n"
                + "def Impl = class () implements Sub (\n"
                + "  def req = fn (x : int) : int -> ( x + 1 )\n"
                + "  def prop = \"ok\"\n"
                + "  def subDef = fn (d : double) : double -> ( d * 2.0 )\n"
                + "  def run = fn () -> ( null )\n"
                + ")\n";

        BabbleParser.FileContext file = BabbleScriptEngine.parseSource(source, "hier.ba");
        ScopeAnalysis analysis = BlockScopes.analyze(file);
        Assertions.assertEquals(List.of(), analysis.typeDiagnostics());

        LoweringTargets.TypeHierarchyInfo base = findByName(analysis, "Base");
        LoweringTargets.TypeHierarchyInfo box = findByName(analysis, "Box");
        LoweringTargets.TypeHierarchyInfo huge = findByName(analysis, "Huge");
        LoweringTargets.TypeHierarchyInfo sub = findByName(analysis, "Sub");
        LoweringTargets.TypeHierarchyInfo impl = findByName(analysis, "Impl");

        // Interfaces list own members and strict transitive closure (excluding self).
        Assertions.assertEquals(ClassShape.Kind.INTERFACE, base.kind());
        Assertions.assertEquals(List.of(), base.directParents());
        Assertions.assertEquals(Set.of(), base.allBabbleInterfaceIds());
        Assertions.assertEquals(Set.of(), base.allJavaInterfaces());

        LoweringTargets.MemberSpec reqSpec = base.interfaceMembers().get("req");
        Assertions.assertFalse(reqSpec.hasDefault());
        Assertions.assertTrue(reqSpec.required());
        Assertions.assertTrue(reqSpec.callable());
        Assertions.assertArrayEquals(new PrimitiveKind[]{PrimitiveKind.INT},
                reqSpec.declaredSignature().parameterKinds());
        Assertions.assertEquals(PrimitiveKind.INT, reqSpec.declaredSignature().returnKind());

        LoweringTargets.MemberSpec propSpec = base.interfaceMembers().get("prop");
        Assertions.assertFalse(propSpec.hasDefault());
        Assertions.assertTrue(propSpec.required());
        Assertions.assertFalse(propSpec.callable());
        Assertions.assertNull(propSpec.declaredSignature());

        LoweringTargets.MemberSpec defMethodSpec = base.interfaceMembers().get("defMethod");
        Assertions.assertTrue(defMethodSpec.hasDefault());
        Assertions.assertFalse(defMethodSpec.required());
        Assertions.assertTrue(defMethodSpec.callable());

        // TypeVar erases to PrimitiveKind.NONE.
        LoweringTargets.MemberSpec boxPut = box.interfaceMembers().get("put");
        Assertions.assertTrue(boxPut.callable());
        Assertions.assertArrayEquals(new PrimitiveKind[]{PrimitiveKind.NONE},
                boxPut.declaredSignature().parameterKinds());
        Assertions.assertEquals(PrimitiveKind.NONE, boxPut.declaredSignature().returnKind());
        Assertions.assertEquals(PrimitiveKind.NONE, box.interfaceMembers().get("get").declaredSignature().returnKind());

        // >200 slots leaves declaredSignature null while preserving callable=true.
        LoweringTargets.MemberSpec bigSpec = huge.interfaceMembers().get("big");
        Assertions.assertTrue(bigSpec.callable());
        Assertions.assertNull(bigSpec.declaredSignature());

        // Sub interface lists direct parents and own members.
        Assertions.assertEquals(Set.of(base.id()), sub.allBabbleInterfaceIds());
        Assertions.assertEquals(Set.of(Runnable.class), sub.allJavaInterfaces());
        Assertions.assertEquals(Set.of("subDef"), sub.interfaceMembers().keySet());

        // Concrete Impl merges transitive interface members and keeps hasDefault=true even when overriding subDef.
        Assertions.assertEquals(Set.of(sub.id(), base.id()), impl.allBabbleInterfaceIds());
        Assertions.assertEquals(Set.of(Runnable.class), impl.allJavaInterfaces());
        Assertions.assertEquals(Set.of("subDef", "req", "prop", "defMethod"), impl.interfaceMembers().keySet());
        Assertions.assertTrue(impl.interfaceMembers().get("subDef").hasDefault());
        Assertions.assertFalse(impl.interfaceMembers().get("subDef").required());
        Assertions.assertTrue(impl.interfaceMembers().get("req").required());
        Assertions.assertTrue(impl.interfaceMembers().get("defMethod").hasDefault());
    }

    @Test
    public void testCrossModuleAndCrossReplTypeHierarchyLookup() throws Exception {
        MapModuleSource src = new MapModuleSource()
                .put("base.ba", "export def RootI = interface ( def r = fn () : int -> ( 1 ) )\n")
                .put("mid.ba", "def b = import(\"base.ba\")\nexport def MidI = interface extends b.RootI ( def m : fn () : int )\n")
                .put("main.ba", "def mid = import(\"mid.ba\")\ndef C = class () implements mid.MidI ( def m = fn () : int -> ( 2 ) )\n");

        ScopeAnalysis mainAnalysis = analyzeWithModules(src, "main.ba");
        Assertions.assertNotNull(mainAnalysis.hierarchy().typeHierarchy("base.ba#RootI"));
        Assertions.assertNotNull(mainAnalysis.hierarchy().typeHierarchy("mid.ba#MidI"));
        LoweringTargets.TypeHierarchyInfo c = findByName(mainAnalysis, "C");
        Assertions.assertEquals(Set.of("mid.ba#MidI", "base.ba#RootI"), c.allBabbleInterfaceIds());
        Assertions.assertTrue(c.interfaceMembers().get("r").hasDefault());
        Assertions.assertTrue(c.interfaceMembers().get("m").required());

        // Across REPL inputs
        AnalysisSession session = new AnalysisSession();
        ScopeAnalysis first = BlockScopes.analyze(BabbleScriptEngine.parseSource(
                "def RI = interface ( def v : int )", "repl1.ba"), session);
        Assertions.assertEquals(List.of(), first.typeDiagnostics());
        LoweringTargets.TypeHierarchyInfo ri = findByName(first, "RI");
        session.commit(first);

        ScopeAnalysis second = BlockScopes.analyze(BabbleScriptEngine.parseSource(
                "def RC = class () implements RI ( def v = 7 )", "repl2.ba"), session);
        Assertions.assertEquals(List.of(), second.typeDiagnostics());
        Assertions.assertSame(ri, second.hierarchy().typeHierarchy(ri.id()));
        LoweringTargets.TypeHierarchyInfo rc = findByName(second, "RC");
        Assertions.assertEquals(Set.of(ri.id()), rc.allBabbleInterfaceIds());
        Assertions.assertTrue(rc.interfaceMembers().get("v").required());
    }

    @Test
    public void testSuperTargetInfoQualifiedAndUnqualified() throws Exception {
        String source = ""
                + "def Root = interface ( def f = fn () : int -> ( 1 ) )\n"
                + "def Mid = interface extends Root ()\n"
                + "def Other = interface ( def f = fn () : int -> ( 2 ) )\n"
                + "def Sub = interface extends Mid ( def m = fn () : int -> ( super.f() ) )\n"
                + "def C = class () implements Mid, Other (\n"
                + "  def f = fn () : int -> ( super(Mid).f() + super(Other).f() )\n"
                + ")\n"
                + "def Comp = class () implements java.util.Comparator of any? (\n"
                + "  def compare = fn (a : any?, b : any?) : int -> ( 0 )\n"
                + "  def rev = fn io shared () -> ( super.reversed() )\n"
                + ")\n";

        BabbleParser.FileContext file = BabbleScriptEngine.parseSource(source, "super.ba");
        ScopeAnalysis analysis = BlockScopes.analyze(file);
        Assertions.assertEquals(List.of(), analysis.typeDiagnostics());

        LoweringTargets.TypeHierarchyInfo root = findByName(analysis, "Root");
        LoweringTargets.TypeHierarchyInfo mid = findByName(analysis, "Mid");
        LoweringTargets.TypeHierarchyInfo other = findByName(analysis, "Other");
        LoweringTargets.TypeHierarchyInfo sub = findByName(analysis, "Sub");
        LoweringTargets.TypeHierarchyInfo cls = findByName(analysis, "C");
        LoweringTargets.TypeHierarchyInfo comp = findByName(analysis, "Comp");

        List<BabbleParser.SuperExpressionContext> superExprs = supers(file);
        Assertions.assertEquals(4, superExprs.size());

        LoweringTargets.SuperTargetInfo subSuper = analysis.hierarchy().superTargetInfo(superExprs.get(0));
        Assertions.assertEquals(new LoweringTargets.SuperTargetInfo(sub.id(), true, mid.id(), null, root.id(), null, "f"), subSuper);

        LoweringTargets.SuperTargetInfo cMid = analysis.hierarchy().superTargetInfo(superExprs.get(1));
        Assertions.assertEquals(new LoweringTargets.SuperTargetInfo(cls.id(), false, mid.id(), null, root.id(), null, "f"), cMid);

        LoweringTargets.SuperTargetInfo cOther = analysis.hierarchy().superTargetInfo(superExprs.get(2));
        Assertions.assertEquals(new LoweringTargets.SuperTargetInfo(cls.id(), false, other.id(), null, other.id(), null, "f"), cOther);

        LoweringTargets.SuperTargetInfo javaSuper = analysis.hierarchy().superTargetInfo(superExprs.get(3));
        Assertions.assertEquals(new LoweringTargets.SuperTargetInfo(comp.id(), false, null, java.util.Comparator.class,
                null, java.util.Comparator.class, "reversed"), javaSuper);
    }

    @Test
    public void testIsTargetRecordingAndExclusions() throws Exception {
        MapModuleSource src = new MapModuleSource()
                .put("mod.ba", ""
                        + "def Priv = interface ()\n"
                        + "export def Exp = interface ()\n"
                        + "export def Alias = Priv\n")
                .put("main.ba", ""
                        + "def m = import(\"mod.ba\")\n"
                        + "def TopC = class () ()\n"
                        + "def TopI = interface ()\n"
                        + "def TopAlias = TopC\n"
                        + "def f = fn (x : any?) -> (\n"
                        + "  def LocalC = class () ()\n"
                        + "  discard(x is TopC)\n"           // 0: positive
                        + "  discard(x is (TopAlias))\n"     // 1: positive (parenthesized alias)
                        + "  discard(x is TopI)\n"           // 2: positive
                        + "  discard(x is m.Exp)\n"          // 3: positive
                        + "  discard(x is m.Alias)\n"        // 4: positive
                        + "  discard(x is LocalC)\n"         // 5: negative (inside function)
                        + "  discard(x is int)\n"            // 6: negative (builtin)
                        + "  x is java.lang.String\n"        // 7: negative (Java class)
                        + ")\n");

        Map<String, ModuleInterface> cache = new HashMap<>();
        ScopeAnalysis analysis = analyzeModule(src, "main.ba", cache);

        BabbleParser.FileContext parsedMain = (BabbleParser.FileContext) analysis.pass().fileScope.owner;
        List<BabbleParser.IsExpressionContext> isList = isExpressions(parsedMain);
        Assertions.assertEquals(8, isList.size());

        Assertions.assertNotNull(analysis.lowering().isTarget(isList.get(0)));
        Assertions.assertEquals(ClassShape.Kind.CLASS, analysis.lowering().isTarget(isList.get(0)).kind());
        Assertions.assertEquals(analysis.lowering().isTarget(isList.get(0)).typeId(), analysis.lowering().isTarget(isList.get(1)).typeId());
        Assertions.assertEquals(ClassShape.Kind.INTERFACE, analysis.lowering().isTarget(isList.get(2)).kind());
        Assertions.assertEquals("mod.ba#Exp", analysis.lowering().isTarget(isList.get(3)).typeId());
        Assertions.assertEquals("mod.ba#Priv", analysis.lowering().isTarget(isList.get(4)).typeId());

        for (int i = 5; i < 8; i++) {
            Assertions.assertNull(analysis.lowering().isTarget(isList.get(i)), "Expected null IsTarget at index " + i);
        }

        // Cross-REPL reassignment invalidates static type alias & IsTarget
        AnalysisSession session = new AnalysisSession();
        ScopeAnalysis r1 = BlockScopes.analyze(BabbleScriptEngine.parseSource(
                "def C1 = class () ()\n def C2 = class () ()\n var RA : any = C1", "r1.ba"), session);
        session.commit(r1);
        ScopeAnalysis r2 = BlockScopes.analyze(BabbleScriptEngine.parseSource(
                "RA = C2", "r2.ba"), session);
        session.commit(r2);
        ScopeAnalysis r3 = BlockScopes.analyze(BabbleScriptEngine.parseSource(
                "def check = fn (x : any) -> ( discard(x is C1) ; x is RA )", "r3c.ba"), session);
        Assertions.assertEquals(1, r3.typeDiagnostics().size());
        Assertions.assertEquals("Unknown type : RA", r3.typeDiagnostics().get(0).message());
        List<BabbleParser.IsExpressionContext> r3IsList = isExpressions((BabbleParser.FileContext) r3.pass().fileScope.owner);
        Assertions.assertNull(r3.lowering().isTarget(r3IsList.get(1)));
    }

    @Test
    public void testInterfaceCallTargetRecordingAndExclusions() throws Exception {
        String source = ""
                + "def Base = interface (\n"
                + "  def baseFn : fn (x : int) : int\n"
                + "  def defSelf = fn (a : int) : int -> ( this.baseFn(a) )\n"
                + ")\n"
                + "def Box of T = interface extends Base (\n"
                + "  def get : fn () : T\n"
                + "  def id of U = fn (u : U) : U -> ( u )\n"
                + "  def opt = fn (a : int, b : int = 2) : int -> ( a + b )\n"
                + ")\n"
                + "def Alt = interface ( def baseFn : fn (x : int) : int )\n"
                + "def Impl = class () implements Box of int (\n"
                + "  def baseFn = fn (x : int) : int -> ( x )\n"
                + "  def get = fn () : int -> ( 1 )\n"
                + ")\n"
                + "def caller = fn io (b : Box of int, mb : (Box of int)?, u : Base | Alt, c : Impl, shared r : java.lang.Runnable) -> (\n"
                + "  def r1 = b.baseFn(1)\n"
                + "  def r2 = b.get()\n"
                + "  def r3 = b.id(42)\n"
                + "  def r4 = b.opt(1)\n"          // optional omitted -> null
                + "  def r5 = b.opt(a: 1, b: 2)\n" // named args -> null
                + "  def r6 = mb?.baseFn(1)\n"     // safe nav -> null
                + "  def r7 = u.baseFn(1)\n"       // union receiver -> null
                + "  def r8 = c.baseFn(1)\n"       // concrete class receiver -> null
                + "  r.run()\n"                    // Java interface -> null
                + ")\n";

        ScopeAnalysis analysis = analyzeSingle(source);
        Assertions.assertEquals(List.of(), analysis.typeDiagnostics(), analysis.typeDiagnostics().toString());
        BabbleParser.FileContext file = (BabbleParser.FileContext) analysis.pass().fileScope.owner;
        LoweringTargets.TypeHierarchyInfo base = findByName(analysis, "Base");
        LoweringTargets.TypeHierarchyInfo box = findByName(analysis, "Box");

        List<BabbleParser.CallContext> allCalls = calls(file);
        Assertions.assertEquals(10, allCalls.size());

        // 0: this.baseFn(a) inside Base.defSelf
        LoweringTargets.InterfaceCallTarget c0 = analysis.lowering().interfaceCallTarget(allCalls.get(0));
        Assertions.assertNotNull(c0);
        Assertions.assertEquals(base.id(), c0.interfaceTypeId());
        Assertions.assertEquals("baseFn", c0.memberName());
        Assertions.assertArrayEquals(new PrimitiveKind[]{PrimitiveKind.INT}, c0.signature().parameterKinds());
        Assertions.assertEquals(PrimitiveKind.INT, c0.signature().returnKind());
        Assertions.assertTrue(c0.guaranteedCallable());

        // 1: b.baseFn(1) declared on Base (not Box)
        LoweringTargets.InterfaceCallTarget c1 = analysis.lowering().interfaceCallTarget(allCalls.get(1));
        Assertions.assertNotNull(c1);
        Assertions.assertEquals(base.id(), c1.interfaceTypeId());
        Assertions.assertEquals("baseFn", c1.memberName());

        // 2: b.get() on Box(int) uses uninstantiated TypeVar -> NONE
        LoweringTargets.InterfaceCallTarget c2 = analysis.lowering().interfaceCallTarget(allCalls.get(2));
        Assertions.assertNotNull(c2);
        Assertions.assertEquals(box.id(), c2.interfaceTypeId());
        Assertions.assertEquals("get", c2.memberName());
        Assertions.assertEquals(PrimitiveKind.NONE, c2.signature().returnKind());

        // 3: b.id(42) generic interface method uses TypeVar -> NONE parameter & return
        LoweringTargets.InterfaceCallTarget c3 = analysis.lowering().interfaceCallTarget(allCalls.get(3));
        Assertions.assertNotNull(c3);
        Assertions.assertEquals(box.id(), c3.interfaceTypeId());
        Assertions.assertEquals("id", c3.memberName());
        Assertions.assertArrayEquals(new PrimitiveKind[]{PrimitiveKind.NONE}, c3.signature().parameterKinds());
        Assertions.assertEquals(PrimitiveKind.NONE, c3.signature().returnKind());

        // 4: b.opt(1) optional omitted -> lowered via InterfaceCallTarget with optionalParamBits (BIP021-I1)
        LoweringTargets.InterfaceCallTarget c4 = analysis.lowering().interfaceCallTarget(allCalls.get(4));
        Assertions.assertNotNull(c4);
        Assertions.assertEquals(box.id(), c4.interfaceTypeId());
        Assertions.assertEquals("opt", c4.memberName());
        Assertions.assertEquals(2, c4.signature().optionalParamBits());
        Assertions.assertEquals(2, analysis.calls().argumentBinding(allCalls.get(4)).missingParamBits());

        // 5: b.opt(a: 1, b: 2) named args -> lowered via InterfaceCallTarget (BIP021-I1)
        LoweringTargets.InterfaceCallTarget c5 = analysis.lowering().interfaceCallTarget(allCalls.get(5));
        Assertions.assertNotNull(c5);
        Assertions.assertEquals(box.id(), c5.interfaceTypeId());
        Assertions.assertEquals("opt", c5.memberName());
        Assertions.assertEquals(2, c5.signature().optionalParamBits());
        Assertions.assertEquals(0, analysis.calls().argumentBinding(allCalls.get(5)).missingParamBits());

        // 6..9: all excluded
        for (int i = 6; i < 10; i++) {
            Assertions.assertNull(analysis.lowering().interfaceCallTarget(allCalls.get(i)), "Expected null InterfaceCallTarget at call " + i);
        }
    }

    private static LoweringTargets.TypeHierarchyInfo findByName(ScopeAnalysis analysis, String name) {
        return analysis.hierarchy().typeHierarchies().values().stream()
                .filter(info -> name.equals(info.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing type hierarchy for " + name));
    }
}
