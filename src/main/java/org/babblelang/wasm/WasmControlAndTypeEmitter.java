package org.babblelang.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;

/**
 * Emits Wasm 3.0 runtime type checks/casts ({@code is}, {@code as}, {@code !}) and structured
 * control flow ({@code block}, {@code if}, {@code while}, {@code break}, {@code continue}, {@code
 * return}, {@code throw}, and 2-copy {@code try/catch/finally}) on behalf of {@link
 * WasmFuncEmitter}.
 */
final class WasmControlAndTypeEmitter {
    private final WasmFuncEmitter fn;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;
    private final WasmArrayOpsGen arrOps;
    private final WasmObjectSystem objects;

    WasmControlAndTypeEmitter(WasmProgramLinker linker, WasmFuncEmitter fn) {
        this.fn = fn;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.ops = linker.ops();
        this.arrOps = linker.arrays();
        this.objects = linker.objects();
    }

    // ---------------------------------------------------------------- is, as, non-null

    void emitIs(HirExpr.Is i) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        fn.emitBoxed(i.left());
        int valLocal = ctx.newLocal("$isVal", ts.anyRef());
        c.localSet(valLocal);
        if (i.descriptor() != null && !i.descriptor().isEmpty()) {
            emitDescCheck(c, valLocal, WasmTypeDesc.parse(i.descriptor()), false);
            ctx.stackKind = PrimitiveKind.BOOL;
            return;
        }
        if (objects.emitNominalIsCheck(c, valLocal, i.target(), i.intersectionTargets())) {
            ctx.stackKind = PrimitiveKind.BOOL;
            return;
        }
        if (i.builtin() != null) {
            int bid = WasmRuntimeOpsGen.builtinId(i.builtin());
            if (bid >= 0) {
                c.localGet(valLocal).i32Const(bid).call(ops.fnIsBuiltin);
                ctx.stackKind = PrimitiveKind.BOOL;
                return;
            }
        }
        // Null descriptor with no nominal/builtin target means `is any?` (Types.matches returns
        // true)
        c.i32Const(1);
        ctx.stackKind = PrimitiveKind.BOOL;
    }

    /**
     * Emits an i32 (0/1) type check for {@code valLocal} against a parsed {@link WasmTypeDesc}.
     * When {@code forAsCast} is true, numeric widening (e.g. {@code int} to {@code double}) is
     * accepted to match {@code org.babblelang.runtime.Types.Builtin#accepts}.
     */
    void emitDescCheck(WasmCodeBuilder c, int valLocal, WasmTypeDesc desc, boolean forAsCast) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        if (desc == null) {
            c.i32Const(1);
            return;
        }
        switch (desc.kind()) {
            case "null" -> c.localGet(valLocal).refIsNull();
            case "any" -> c.localGet(valLocal).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ);
            case "fn" -> {
                c.localGet(valLocal)
                        .refTest(ts.fnBaseType)
                        .localGet(valLocal)
                        .refTest(ts.classValType)
                        .op(WasmInstr.SimpleOp.I32_OR)
                        .localGet(valLocal)
                        .refTest(ts.overloadSetType)
                        .op(WasmInstr.SimpleOp.I32_OR);
            }
            case "babble" -> {
                if (!objects.emitNominalIsCheckById(c, valLocal, desc.payload())) {
                    c.i32Const(0);
                }
            }
            case "union" -> {
                List<WasmTypeDesc> ms = desc.members();
                if (ms.isEmpty()) {
                    c.i32Const(0);
                    return;
                }
                for (int k = 0; k < ms.size(); k++) {
                    emitDescCheck(c, valLocal, ms.get(k), forAsCast);
                    if (k > 0) c.op(WasmInstr.SimpleOp.I32_OR);
                }
            }
            case "inter" -> {
                List<WasmTypeDesc> ms = desc.members();
                if (ms.isEmpty()) {
                    c.i32Const(1);
                    return;
                }
                List<LoweringTargets.IsTarget> targets = new ArrayList<>();
                boolean allNominal = true;
                for (WasmTypeDesc m : ms) {
                    if ("babble".equals(m.kind())) {
                        targets.add(
                                new LoweringTargets.IsTarget(
                                        m.payload(),
                                        org.babblelang.runtime.ClassShape.Kind.INTERFACE,
                                        null));
                    } else {
                        allNominal = false;
                        break;
                    }
                }
                if (allNominal && objects.emitNominalIsCheck(c, valLocal, null, targets)) {
                    return;
                }
                for (int k = 0; k < ms.size(); k++) {
                    emitDescCheck(c, valLocal, ms.get(k), forAsCast);
                    if (k > 0) c.op(WasmInstr.SimpleOp.I32_AND);
                }
            }
            case "java" -> {
                String fqcn = desc.payload();
                switch (fqcn) {
                    case "java.lang.String", "java.lang.CharSequence" ->
                            c.localGet(valLocal).refTest(ts.stringType);
                    case "java.lang.Object" ->
                            c.localGet(valLocal).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ);
                    case "java.util.List" -> c.localGet(valLocal).refTest(ts.listType);
                    case "java.util.Set" -> c.localGet(valLocal).refTest(ts.setType);
                    case "java.util.Collection" ->
                            c.localGet(valLocal)
                                    .refTest(ts.listType)
                                    .localGet(valLocal)
                                    .refTest(ts.setType)
                                    .op(WasmInstr.SimpleOp.I32_OR);
                    case "java.util.SortedSet" ->
                            c.localGet(valLocal)
                                    .refTest(ts.setType)
                                    .if_(WasmValType.I32)
                                    .localGet(valLocal)
                                    .refCast(ts.setType)
                                    .structGet(ts.setType, 2)
                                    .else_()
                                    .i32Const(0)
                                    .end();
                    case "java.util.Map" -> c.localGet(valLocal).refTest(ts.mapType);
                    case "java.util.SortedMap" ->
                            c.localGet(valLocal)
                                    .refTest(ts.mapType)
                                    .if_(WasmValType.I32)
                                    .localGet(valLocal)
                                    .refCast(ts.mapType)
                                    .structGet(ts.mapType, 3)
                                    .else_()
                                    .i32Const(0)
                                    .end();
                    case "java.util.Map$Entry" -> c.localGet(valLocal).refTest(ts.entryType);
                    case "org.babblelang.runtime.Range" ->
                            c.localGet(valLocal).refTest(ts.rangeBaseType);
                    case "org.babblelang.runtime.IntRange", "org.babblelang.runtime.CharRange" ->
                            c.localGet(valLocal).refTest(ts.intRangeType);
                    case "org.babblelang.runtime.LongRange" ->
                            c.localGet(valLocal).refTest(ts.longRangeType);
                    case "org.babblelang.runtime.DoubleRange" ->
                            c.localGet(valLocal).refTest(ts.doubleRangeType);
                    case "org.babblelang.runtime.ComparableRange" ->
                            c.localGet(valLocal).refTest(ts.stringRangeType);
                    case "org.babblelang.runtime.BabbleException",
                            "java.lang.Throwable",
                            "java.lang.Exception",
                            "java.lang.RuntimeException" ->
                            c.localGet(valLocal).refTest(ts.babbleExnType);
                    case "java.lang.Iterable" -> {
                        c.localGet(valLocal)
                                .refTest(ts.listType)
                                .localGet(valLocal)
                                .refTest(ts.setType)
                                .op(WasmInstr.SimpleOp.I32_OR)
                                .localGet(valLocal)
                                .refTest(ts.intRangeType)
                                .op(WasmInstr.SimpleOp.I32_OR)
                                .localGet(valLocal)
                                .refTest(ts.longRangeType)
                                .op(WasmInstr.SimpleOp.I32_OR);
                    }
                    case "java.util.Iterator" ->
                            c.localGet(valLocal)
                                    .refTest(ts.collectionIterType)
                                    .localGet(valLocal)
                                    .refTest(ts.rangeIterType)
                                    .op(WasmInstr.SimpleOp.I32_OR);
                    case "java.lang.Appendable" -> {
                        c.localGet(valLocal).refTest(ts.hostStreamType);
                        for (WasmObjectSystem.ClassInfo ci : objects.allClasses()) {
                            if (ci.hir.javaInterfaces().contains(Appendable.class)) {
                                c.localGet(valLocal)
                                        .refTest(ci.structTypeIdx)
                                        .op(WasmInstr.SimpleOp.I32_OR);
                            }
                        }
                    }
                    case "java.io.Writer", "java.io.PrintWriter" -> {
                        c.localGet(valLocal).refTest(ts.hostStreamType);
                    }
                    default -> {
                        if (fqcn.startsWith("[")) {
                            arrOps.emitArrayTypeCheck(fn, valLocal, fqcn);
                        } else {
                            c.i32Const(0);
                        }
                    }
                }
            }
            default -> {
                if (forAsCast && isNumericBuiltinKind(desc.kind())) {
                    int targetRank = numericRankOf(desc.kind());
                    // Accept any numeric type with rank <= targetRank (matches
                    // JavaTypes.isWidening)
                    int rLocal = ctx.newLocal("$asRank", WasmValType.I32);
                    c.localGet(valLocal)
                            .call(rt.fnNumRank)
                            .localTee(rLocal)
                            .i32Const(0)
                            .op(WasmInstr.SimpleOp.I32_GE_S)
                            .localGet(rLocal)
                            .i32Const(targetRank)
                            .op(WasmInstr.SimpleOp.I32_LE_S)
                            .op(WasmInstr.SimpleOp.I32_AND);
                    return;
                }
                int bid = WasmRuntimeOpsGen.builtinId(desc.kind());
                if (bid >= 0) {
                    c.localGet(valLocal).i32Const(bid).call(ops.fnIsBuiltin);
                } else if (desc.kind().startsWith("array")) {
                    arrOps.emitArrayTypeCheck(fn, valLocal, desc.payload());
                } else {
                    c.i32Const(0);
                }
            }
        }
    }

    private static boolean isNumericBuiltinKind(String k) {
        return switch (k) {
            case "byte", "short", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }

    private static int numericRankOf(String k) {
        return switch (k) {
            case "byte" -> 0;
            case "short" -> 1;
            case "int" -> 2;
            case "long" -> 3;
            case "float" -> 4;
            case "double" -> 5;
            default -> -1;
        };
    }

    void emitAsExpr(HirExpr.As a) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        String descStr = a.targetDescriptor();
        WasmTypeDesc desc = descStr != null ? WasmTypeDesc.parse(descStr) : null;
        if (desc == null && a.target() != null) {
            desc = new WasmTypeDesc("babble", a.target().typeId(), a.target().typeId(), List.of());
        }
        if (desc == null) {
            fn.emitAs(a.left(), a.meta().kind());
            return;
        }
        fn.emitBoxed(a.left());
        int tmp = ctx.newLocal("$asTmp", ts.anyRef());
        c.localSet(tmp);
        emitDescCheck(c, tmp, desc, true);
        c.op(WasmInstr.SimpleOp.I32_EQZ).if_();
        if ("babble".equals(desc.kind()) && desc.payload() != null && !desc.payload().isEmpty()) {
            int gotStr = ctx.newLocal("$asGot", WasmValType.refNull(ts.stringType));
            c.localGet(tmp).call(ops.fnDescribeType).localTee(gotStr).refAsNonNull();
            rt.emitStringLit(c, desc.display());
            c.call(rt.fnStrEq)
                    .localGet(tmp)
                    .refTest(ts.objectType)
                    .op(WasmInstr.SimpleOp.I32_AND)
                    .if_();
            rt.emitStringLit(c, "Type error : expected " + desc.display() + ", got ");
            c.localGet(gotStr).refAsNonNull().call(rt.fnStrConcat);
            rt.emitStringLit(c, " (expected " + desc.payload() + ", got ");
            c.call(rt.fnStrConcat)
                    .localGet(tmp)
                    .refCast(ts.objectType)
                    .structGet(ts.objectType, 0)
                    .structGet(ts.vtableType, 12)
                    .call(rt.fnStrConcat);
            rt.emitStringLit(c, ")");
            c.call(rt.fnStrConcat)
                    .i32Const(a.meta().line())
                    .call(rt.fnThrowMsg)
                    .unreachable()
                    .end();
            rt.emitStringLit(c, "Type error : expected " + desc.display() + ", got ");
            c.localGet(gotStr)
                    .refAsNonNull()
                    .call(rt.fnStrConcat)
                    .i32Const(a.meta().line())
                    .call(rt.fnThrowMsg)
                    .unreachable();
        } else {
            rt.emitStringLit(c, "Type error : expected " + desc.display() + ", got ");
            c.localGet(tmp).call(ops.fnDescribeType).call(rt.fnStrConcat);
            c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable();
        }
        c.end();

        // Numeric widening conversion when target is a numeric type or a union containing numeric
        // member(s)
        int[] unionNumRanks = numericTargetRanks(desc);
        if (unionNumRanks.length == 1 && unionNumRanks[0] > 0) {
            int rank = unionNumRanks[0];
            c.localGet(tmp)
                    .refIsNull()
                    .if_(ts.anyRef())
                    .refNull(ts.anyType)
                    .else_()
                    .localGet(tmp)
                    .call(rt.fnNumRank)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .if_(ts.anyRef())
                    .localGet(tmp)
                    .i32Const(rank)
                    .call(rt.fnWidenNumeric)
                    .else_()
                    .localGet(tmp)
                    .end()
                    .end();
        } else if (unionNumRanks.length > 1) {
            // Multiple numeric members sorted ascending: widen to the smallest rank >= actual rank
            int rLocal = ctx.newLocal("$asURank", WasmValType.I32);
            c.localGet(tmp)
                    .call(rt.fnNumRank)
                    .localTee(rLocal)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .if_(ts.anyRef());
            for (int ri = 0; ri < unionNumRanks.length; ri++) {
                int targetR = unionNumRanks[ri];
                if (ri < unionNumRanks.length - 1) {
                    c.localGet(rLocal)
                            .i32Const(targetR)
                            .op(WasmInstr.SimpleOp.I32_LE_S)
                            .if_(ts.anyRef())
                            .localGet(tmp)
                            .i32Const(targetR)
                            .call(rt.fnWidenNumeric)
                            .else_();
                } else {
                    c.localGet(tmp).i32Const(targetR).call(rt.fnWidenNumeric);
                }
            }
            for (int ri = 0; ri < unionNumRanks.length - 1; ri++) {
                c.end();
            }
            c.else_().localGet(tmp).end();
        } else {
            c.localGet(tmp);
        }
        ctx.stackKind = PrimitiveKind.NONE;
        if (a.meta().kind().isPrimitive()) {
            fn.convertStack(PrimitiveKind.NONE, a.meta().kind(), a.meta().line());
        }
    }

    private static int[] numericTargetRanks(WasmTypeDesc desc) {
        if (isNumericBuiltinKind(desc.kind())) {
            return new int[] {numericRankOf(desc.kind())};
        }
        if ("union".equals(desc.kind())) {
            java.util.TreeSet<Integer> ranks = new java.util.TreeSet<>();
            collectNumericUnionRanks(desc, ranks);
            if (!ranks.isEmpty()) {
                return ranks.stream().mapToInt(Integer::intValue).toArray();
            }
        }
        return new int[0];
    }

    private static void collectNumericUnionRanks(WasmTypeDesc desc, java.util.Set<Integer> out) {
        if (isNumericBuiltinKind(desc.kind())) {
            out.add(numericRankOf(desc.kind()));
        } else if ("union".equals(desc.kind())) {
            for (WasmTypeDesc m : desc.members()) {
                collectNumericUnionRanks(m, out);
            }
        }
    }

    void emitNonNull(HirExpr.NonNull n) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        fn.emitBoxed(n.operand());
        int tmp = ctx.newLocal("$nnTmp", ts.anyRef());
        c.localTee(tmp).refIsNull().if_();
        rt.emitStringLit(
                c,
                "Type error : expected "
                        + (n.expectedType() != null ? n.expectedType() : "any")
                        + ", got null");
        c.i32Const(n.meta().line()).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp);
        ctx.stackKind = PrimitiveKind.NONE;
        if (n.meta().kind().isPrimitive()) {
            fn.convertStack(PrimitiveKind.NONE, n.meta().kind(), n.meta().line());
        }
    }

    // ---------------------------------------------------------------- control flow: block, if,
    // while, break, continue, return

    void emitBlock(HirExpr.Block b) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        if (b.opensNamespaceScope()) {
            Map<String, Integer> scope = new HashMap<>();
            for (String name : b.declaredNames()) {
                int local = ctx.newLocal("ns_" + name, ts.anyRef());
                ctx.code.globalGet(rt.gUndefined).localSet(local);
                scope.put(name, local);
            }
            ctx.nsScopes.add(scope);
        }
        for (HirVar v : b.declaredVars()) {
            fn.allocateLocalVar(v);
        }
        try {
            fn.emitStmtList(b.statements(), b.meta().kind());
            fn.widenStackRank(b.innerWideningRank(), b.meta().line());
        } finally {
            if (b.opensNamespaceScope()) {
                ctx.nsScopes.remove(ctx.nsScopes.size() - 1);
            }
        }
    }

    void emitIf(HirExpr.If i) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        PrimitiveKind resKind = i.meta().kind();
        fn.emitTruthValue(i.cond());
        c.if_(ts.valTypeFor(resKind));
        ctx.pushBlock("if");
        fn.emitAs(i.thenBranch(), resKind);
        c.else_();
        if (i.elseBranch() != null) {
            fn.emitAs(i.elseBranch(), resKind);
        } else {
            ts.emitDefaultValue(c, ts.valTypeFor(resKind));
        }
        ctx.popBlock();
        c.end();
        ctx.stackKind = resKind;
    }

    void emitWhile(HirExpr.While w) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        PrimitiveKind resKind = w.meta().kind();
        int resLocal = ctx.newLocal("$whileRes", ts.valTypeFor(resKind));
        ts.emitDefaultValue(c, ts.valTypeFor(resKind));
        c.localSet(resLocal);
        int breakDepth = ctx.pushBlock("while_brk");
        c.block(ts.valTypeFor(resKind));
        int contDepth = ctx.pushBlock("while_loop");
        c.loop();
        WasmFuncEmitter.LoopFrame loop =
                new WasmFuncEmitter.LoopFrame(breakDepth, contDepth, resKind);
        ctx.loops.push(loop);
        try {
            fn.emitTruthValue(w.cond());
            c.if_();
            ctx.pushBlock("while_if");
            fn.emitAs(w.body(), resKind);
            c.localSet(resLocal);
            c.br(ctx.relDepth(contDepth));
            ctx.popBlock();
            c.end();
        } finally {
            ctx.loops.pop();
        }
        ctx.popBlock();
        c.end(); // end loop
        c.localGet(resLocal);
        ctx.popBlock();
        c.end(); // end block
        ctx.stackKind = resKind;
    }

    void emitBreak(HirExpr.Break b) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmFuncEmitter.LoopFrame targetLoop = ctx.loops.peek();
        if (targetLoop == null) {
            rt.emitStringLit(c, "break outside of loop");
            c.i32Const(b.meta().line()).call(rt.fnThrowMsg).unreachable();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        if (b.value() != null) {
            fn.emitAs(b.value(), targetLoop.resultKind());
        } else {
            ts.emitDefaultValue(c, ts.valTypeFor(targetLoop.resultKind()));
        }
        WasmFuncEmitter.FinallyFrame innerFin = innermostFinallyInside(targetLoop);
        if (innerFin != null) {
            fn.convertRawValue(targetLoop.resultKind(), innerFin.brkKind(), b.meta().line());
            c.localSet(innerFin.brkValLocal())
                    .i32Const(2)
                    .localSet(innerFin.exitKindLocal())
                    .br(ctx.relDepth(innerFin.normalDepth()));
        } else {
            c.br(ctx.relDepth(targetLoop.breakDepth()));
        }
        ctx.stackKind = b.meta().kind();
    }

    void emitContinue() {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmFuncEmitter.LoopFrame targetLoop = ctx.loops.peek();
        if (targetLoop == null) {
            rt.emitStringLit(c, "continue outside of loop");
            c.i32Const(0).call(rt.fnThrowMsg).unreachable();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        WasmFuncEmitter.FinallyFrame innerFin = innermostFinallyInside(targetLoop);
        if (innerFin != null) {
            c.i32Const(3)
                    .localSet(innerFin.exitKindLocal())
                    .br(ctx.relDepth(innerFin.normalDepth()));
        } else {
            c.br(ctx.relDepth(targetLoop.continueDepth()));
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void emitReturn(HirExpr.Return r) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        if (r.value() != null) {
            fn.emitAs(r.value(), ctx.fnReturnKind);
        } else {
            ts.emitDefaultValue(c, ts.valTypeFor(ctx.fnReturnKind));
        }
        WasmFuncEmitter.FinallyFrame innerFin = ctx.finallies.peek();
        if (innerFin != null) {
            c.localSet(innerFin.retValLocal())
                    .i32Const(1)
                    .localSet(innerFin.exitKindLocal())
                    .br(ctx.relDepth(innerFin.normalDepth()));
        } else {
            c.return_();
        }
        ctx.stackKind = r.meta().kind();
    }

    private WasmFuncEmitter.FinallyFrame innermostFinallyInside(WasmFuncEmitter.LoopFrame loop) {
        WasmFuncEmitter.FinallyFrame top = fn.ctx().finallies.peek();
        if (top != null && top.enclosingLoop() == loop) {
            return top;
        }
        return null;
    }

    // ---------------------------------------------------------------- BIP028 try / catch / 2-copy
    // finally / throw (BIP025-D13)

    void emitThrow(HirExpr.Throw t) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        fn.emitBoxed(t.value());
        int tmp = ctx.newLocal("$throwVal", ts.anyRef());
        c.localTee(tmp).refIsNull().if_();
        rt.emitStringLit(c, "Cannot throw null");
        c.i32Const(t.meta().line()).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp).refAsNonNull().i32Const(t.meta().line()).throw_(rt.babbleExnTag);
        ctx.stackKind = t.meta().kind();
    }

    void emitTry(HirExpr.Try t) {
        boolean hasNonEmptyFinally = t.hasFinally() && !t.finallyBody().isEmpty();
        if (!hasNonEmptyFinally) {
            emitTryCatchOnly(t, t.meta().kind());
            return;
        }
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        PrimitiveKind resKind = t.meta().kind();
        int resLocal = ctx.newLocal("$tryRes", ts.valTypeFor(resKind));
        int exitKindLocal = ctx.newLocal("$exitKind", WasmValType.I32);
        int retValLocal = ctx.newLocal("$finRet", ts.valTypeFor(ctx.fnReturnKind));
        WasmFuncEmitter.LoopFrame enclosingLoop = ctx.loops.peek();
        PrimitiveKind brkKind =
                enclosingLoop != null ? enclosingLoop.resultKind() : PrimitiveKind.NONE;
        int brkValLocal = ctx.newLocal("$finBrk", ts.valTypeFor(brkKind));

        int exnValLocal = ctx.newLocal("$finExnVal", ts.anyRef());
        int exnLineLocal = ctx.newLocal("$finExnLine", WasmValType.I32);
        int exnRefLocal = ctx.newLocal("$finExnRef", WasmValType.EXNREF);

        c.i32Const(0).localSet(exitKindLocal);

        // Structured 2-copy finally (BIP025-D13):
        //   block $done (result R)
        //     block $unwind (result (ref $Any) i32 (ref exn))
        //       block $normal
        //         try_table (catch_ref $babble_exn $unwind)
        //           <try + catches -> resLocal>
        //         end
        //       end ;; $normal (normal completion OR intercepted return/break/continue!)
        //       <Copy 1: finallyBody>
        //       <dispatch exitKindLocal: 1=return, 2=break, 3=continue, 0=br $done with resLocal>
        //     end ;; $unwind -> stack: (ref $Any) i32 (ref exn)
        //     local.set $exnRef; local.set $exnLine; local.set $exnVal
        //     <Copy 2: finallyBody (with try_table addSuppressed if !finallyNothrow)>
        //     local.get $exnRef; throw_ref
        //   end ;; $done
        int doneDepth = ctx.pushBlock("try_done");
        c.block(ts.valTypeFor(resKind));

        int unwindDepth = ctx.pushBlock("try_unwind");
        c.blockType(ts.exnCatchRefTripleType);

        int normalDepth = ctx.pushBlock("try_normal");
        c.block();

        WasmFuncEmitter.FinallyFrame finFrame =
                new WasmFuncEmitter.FinallyFrame(
                        normalDepth,
                        exitKindLocal,
                        retValLocal,
                        brkValLocal,
                        brkKind,
                        enclosingLoop);
        ctx.finallies.push(finFrame);
        try {
            int unwindRel = ctx.relDepth(unwindDepth);
            ctx.pushBlock("try_fin_tbl");
            c.tryTable(List.of(WasmInstr.CatchClause.catchRefTag(rt.babbleExnTag, unwindRel)));
            if (t.catches().isEmpty()) {
                emitTryBodyScoped(t, resKind);
            } else {
                emitTryCatchOnly(t, resKind);
            }
            c.localSet(resLocal);
            ctx.popBlock();
            c.end(); // end try_table
        } finally {
            ctx.finallies.pop();
        }

        ctx.popBlock();
        c.end(); // end $normal

        // Copy 1: finally body on normal & control-transfer paths
        Map<Integer, WasmFuncEmitter.VarSlot> savedVarsBeforeFinally = new HashMap<>(ctx.vars);
        emitFinallyBodyScoped(t);

        // Dispatch exitKindLocal: 1=return, 2=break, 3=continue, 0=normal
        c.localGet(exitKindLocal).i32Const(1).op(WasmInstr.SimpleOp.I32_EQ).if_();
        ctx.pushBlock("fin_ret");
        WasmFuncEmitter.FinallyFrame outerFin = ctx.finallies.peek();
        if (outerFin != null) {
            c.localGet(retValLocal)
                    .localSet(outerFin.retValLocal())
                    .i32Const(1)
                    .localSet(outerFin.exitKindLocal())
                    .br(ctx.relDepth(outerFin.normalDepth()));
        } else {
            c.localGet(retValLocal).return_();
        }
        ctx.popBlock();
        c.end();

        if (enclosingLoop != null) {
            c.localGet(exitKindLocal).i32Const(2).op(WasmInstr.SimpleOp.I32_EQ).if_();
            ctx.pushBlock("fin_brk");
            WasmFuncEmitter.FinallyFrame outerLoopFin = innermostFinallyInside(enclosingLoop);
            if (outerLoopFin != null) {
                c.localGet(brkValLocal)
                        .localSet(outerLoopFin.brkValLocal())
                        .i32Const(2)
                        .localSet(outerLoopFin.exitKindLocal())
                        .br(ctx.relDepth(outerLoopFin.normalDepth()));
            } else {
                c.localGet(brkValLocal).br(ctx.relDepth(enclosingLoop.breakDepth()));
            }
            ctx.popBlock();
            c.end();

            c.localGet(exitKindLocal).i32Const(3).op(WasmInstr.SimpleOp.I32_EQ).if_();
            ctx.pushBlock("fin_cont");
            if (outerLoopFin != null) {
                c.i32Const(3)
                        .localSet(outerLoopFin.exitKindLocal())
                        .br(ctx.relDepth(outerLoopFin.normalDepth()));
            } else {
                c.br(ctx.relDepth(enclosingLoop.continueDepth()));
            }
            ctx.popBlock();
            c.end();
        }

        // Normal completion: branch to $done with resLocal
        c.localGet(resLocal).br(ctx.relDepth(doneDepth));

        ctx.popBlock();
        c.end(); // end $unwind -> stack: (ref $Any) i32 (ref exn)

        c.localSet(exnRefLocal).localSet(exnLineLocal).localSet(exnValLocal);

        // Copy 2: finally body on exception unwind path (restore ctx.vars so declared locals/cells
        // allocate cleanly!)
        ctx.vars.clear();
        ctx.vars.putAll(savedVarsBeforeFinally);
        if (t.finallyNothrow()) {
            // BIP028-D5: statically nothrow finally elides inner try_table & addSuppressed!
            emitFinallyBodyScoped(t);
        } else {
            int secVal = ctx.newLocal("$secExnVal", ts.anyRef());
            int secLine = ctx.newLocal("$secExnLine", WasmValType.I32);
            int suppDone = ctx.pushBlock("supp_done");
            c.block();
            int suppCatch = ctx.pushBlock("supp_catch");
            c.blockType(ts.exnCatchPairType);
            int suppRel = ctx.relDepth(suppCatch);
            ctx.pushBlock("supp_tbl");
            c.tryTable(List.of(WasmInstr.CatchClause.catchTag(rt.babbleExnTag, suppRel)));
            emitFinallyBodyScoped(t);
            ctx.popBlock();
            c.end(); // end try_table
            c.br(ctx.relDepth(suppDone));
            ctx.popBlock();
            c.end(); // end $supp_catch -> stack: (ref $Any) i32
            c.localSet(secLine).localSet(secVal);
            c.localGet(exnValLocal)
                    .refAsNonNull()
                    .localGet(secVal)
                    .refAsNonNull()
                    .call(rt.fnAddSuppressed);
            ctx.popBlock();
            c.end(); // end $supp_done
        }
        ctx.vars.clear();
        ctx.vars.putAll(savedVarsBeforeFinally);
        c.localGet(exnRefLocal).refAsNonNull().throwRef();

        ctx.popBlock();
        c.end(); // end $done
        ctx.stackKind = resKind;
    }

    private void emitTryCatchOnly(HirExpr.Try t, PrimitiveKind resKind) {
        if (t.catches().isEmpty()) {
            emitTryBodyScoped(t, resKind);
            return;
        }
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        int exnValLocal = ctx.newLocal("$catchVal", ts.anyRef());
        int exnLineLocal = ctx.newLocal("$catchLine", WasmValType.I32);

        int doneDepth = ctx.pushBlock("tc_done");
        c.block(ts.valTypeFor(resKind));

        int catchDepth = ctx.pushBlock("tc_catch");
        c.blockType(ts.exnCatchPairType);

        int tryValLocal = ctx.newLocal("$tcVal", ts.valTypeFor(resKind));
        int catchRel = ctx.relDepth(catchDepth);
        ctx.pushBlock("tc_tbl");
        // Always use 1-byte empty blocktype (0x40) on try_table: V8 Liftoff in Node 22 miscomputes
        // instruction length for 2-byte blocktypes (e.g. (ref null $t) or s33 >= 64) on try_table.
        c.tryTable(List.of(WasmInstr.CatchClause.catchTag(rt.babbleExnTag, catchRel)));
        emitTryBodyScoped(t, resKind);
        c.localSet(tryValLocal);
        ctx.popBlock();
        c.end(); // end try_table
        c.localGet(tryValLocal).br(ctx.relDepth(doneDepth));

        ctx.popBlock();
        c.end(); // end $tc_catch -> stack: (ref $Any) i32
        c.localSet(exnLineLocal).localSet(exnValLocal);

        // Dispatch catches sequentially
        for (HirExpr.HirCatch hc : t.catches()) {
            emitCatchMatchTest(exnValLocal, hc.target());
            c.if_();
            ctx.pushBlock("catch_body");
            emitCatchBodyScoped(hc, exnValLocal, resKind);
            c.br(ctx.relDepth(doneDepth));
            ctx.popBlock();
            c.end();
        }
        // No catch matched: rethrow!
        c.localGet(exnValLocal).refAsNonNull().localGet(exnLineLocal).throw_(rt.babbleExnTag);

        ctx.popBlock();
        c.end(); // end $tc_done
        ctx.stackKind = resKind;
    }

    private void emitCatchMatchTest(int exnValLocal, LoweringTargets.CatchTarget target) {
        WasmCodeBuilder c = fn.ctx().code;
        if (target == null || target.catchesNonErrorAny() || target.catchesJavaThrowable()) {
            c.i32Const(1);
            return;
        }
        if (target.typeDescriptor() != null && !target.typeDescriptor().isEmpty()) {
            emitDescCheck(c, exnValLocal, WasmTypeDesc.parse(target.typeDescriptor()), false);
            return;
        }
        boolean hasTargets = target.babbleTargets() != null && !target.babbleTargets().isEmpty();
        if (hasTargets) {
            boolean first = true;
            for (LoweringTargets.IsTarget bt : target.babbleTargets()) {
                objects.emitNominalIsCheck(c, exnValLocal, bt, List.of());
                if (!first) {
                    c.op(WasmInstr.SimpleOp.I32_OR);
                }
                first = false;
            }
            return;
        }
        if (target.catchesBabble()) {
            c.localGet(exnValLocal).refTest(ts.babbleExnType);
            return;
        }
        c.i32Const(1);
    }

    private void emitTryBodyScoped(HirExpr.Try t, PrimitiveKind resKind) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        if (t.tryOpensNamespaceScope()) {
            pushNsScope(t.tryDeclaredNames());
        }
        for (HirVar v : t.tryDeclaredVars()) {
            fn.allocateLocalVar(v);
        }
        try {
            fn.emitStmtList(t.tryBody(), resKind);
            fn.widenStackRank(t.tryWideningRank(), t.meta().line());
            fn.convertStack(ctx.stackKind, resKind, t.meta().line());
        } finally {
            if (t.tryOpensNamespaceScope()) {
                ctx.nsScopes.remove(ctx.nsScopes.size() - 1);
            }
        }
    }

    private void emitCatchBodyScoped(HirExpr.HirCatch hc, int exnValLocal, PrimitiveKind resKind) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        if (hc.opensNamespaceScope()) {
            pushNsScope(hc.declaredNames());
        }
        for (HirVar v : hc.declaredVars()) {
            fn.allocateLocalVar(v);
        }
        if (hc.var() != null) {
            fn.allocateLocalVar(hc.var());
            WasmFuncEmitter.VarSlot slot = ctx.vars.get(hc.var().id());
            ctx.code.localGet(exnValLocal);
            fn.convertRawValue(PrimitiveKind.NONE, slot.kind(), hc.line());
            fn.storeVarSlotAndKeep(slot, true);
            ctx.code.drop();
        }
        try {
            fn.emitStmtList(hc.body(), resKind);
            fn.widenStackRank(hc.wideningRank(), hc.line());
            fn.convertStack(ctx.stackKind, resKind, hc.line());
        } finally {
            if (hc.opensNamespaceScope()) {
                ctx.nsScopes.remove(ctx.nsScopes.size() - 1);
            }
        }
    }

    private void emitFinallyBodyScoped(HirExpr.Try t) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        if (t.finallyOpensNamespaceScope()) {
            pushNsScope(t.finallyDeclaredNames());
        }
        for (HirVar v : t.finallyDeclaredVars()) {
            fn.allocateLocalVar(v);
        }
        try {
            for (HirExpr stmt : t.finallyBody()) {
                fn.emitVoid(stmt);
            }
        } finally {
            if (t.finallyOpensNamespaceScope()) {
                ctx.nsScopes.remove(ctx.nsScopes.size() - 1);
            }
        }
    }

    private void pushNsScope(String[] declaredNames) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        Map<String, Integer> scope = new HashMap<>();
        for (String name : declaredNames) {
            int local = ctx.newLocal("ns_" + name, ts.anyRef());
            ctx.code.globalGet(rt.gUndefined).localSet(local);
            scope.put(name, local);
        }
        ctx.nsScopes.add(scope);
    }
}
