package org.babblelang.wasm;

import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;

/**
 * Emits {@code BIP013} {@code for} loops, portable core collection constructors &amp; methods
 * ({@code list}, {@code set}, {@code sortedset}, {@code map}, {@code sortedmap}, {@code entry}),
 * portable {@code string} methods, portable {@code java.lang.Math} / constants, and portable {@code
 * Appendable} calls ({@code BIP025-I3}, {@code BIP025-D4}, {@code BIP025-D6}, {@code BIP025-D12}).
 */
public final class WasmCoreEmitter {
    private final WasmProgramLinker linker;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmArrayOpsGen arrays;
    private final WasmStringOpsGen strings;
    private final WasmCollectionOpsGen cols;
    private final WasmRangeOpsGen ranges;
    private final WasmObjectSystem objects;
    private WasmFuncEmitter fe;

    public WasmCoreEmitter(WasmProgramLinker linker) {
        this.linker = linker;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.arrays = linker.arrays();
        this.strings = linker.strings();
        this.cols = linker.collections();
        this.ranges = linker.ranges();
        this.objects = linker.objects();
    }

    public void setFuncEmitter(WasmFuncEmitter fe) {
        this.fe = fe;
    }

    // ---------------------------------------------------------------- BIP031 First-class ranges

    public void emitRange(HirExpr.Range r) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int incl = r.inclusive() ? 1 : 0;
        switch (r.elemKind()) {
            case BYTE -> {
                c.i32Const(WasmRangeOpsGen.KIND_BYTE).i32Const(incl);
                fe.emitAs(r.start(), PrimitiveKind.BYTE);
                fe.emitAs(r.end(), PrimitiveKind.BYTE);
                c.i32Const(1).i32Const(0).structNew(ts.intRangeType);
            }
            case SHORT -> {
                c.i32Const(WasmRangeOpsGen.KIND_SHORT).i32Const(incl);
                fe.emitAs(r.start(), PrimitiveKind.SHORT);
                fe.emitAs(r.end(), PrimitiveKind.SHORT);
                c.i32Const(1).i32Const(0).structNew(ts.intRangeType);
            }
            case INT -> {
                c.i32Const(WasmRangeOpsGen.KIND_INT).i32Const(incl);
                fe.emitAs(r.start(), PrimitiveKind.INT);
                fe.emitAs(r.end(), PrimitiveKind.INT);
                c.i32Const(1).i32Const(0).structNew(ts.intRangeType);
            }
            case CHAR -> {
                c.i32Const(WasmRangeOpsGen.KIND_CHAR).i32Const(incl);
                fe.emitAs(r.start(), PrimitiveKind.CHAR);
                fe.emitAs(r.end(), PrimitiveKind.CHAR);
                c.i32Const(1).i32Const(0).structNew(ts.intRangeType);
            }
            case LONG -> {
                c.i32Const(WasmRangeOpsGen.KIND_LONG).i32Const(incl);
                fe.emitAs(r.start(), PrimitiveKind.LONG);
                fe.emitAs(r.end(), PrimitiveKind.LONG);
                c.i64Const(1L).i32Const(0).structNew(ts.longRangeType);
            }
            case FLOAT -> {
                c.i32Const(WasmRangeOpsGen.KIND_FLOAT).i32Const(incl);
                fe.emitAs(r.start(), PrimitiveKind.FLOAT);
                c.op(WasmInstr.SimpleOp.F64_PROMOTE_F32);
                fe.emitAs(r.end(), PrimitiveKind.FLOAT);
                c.op(WasmInstr.SimpleOp.F64_PROMOTE_F32);
                c.structNew(ts.doubleRangeType);
            }
            case DOUBLE -> {
                c.i32Const(WasmRangeOpsGen.KIND_DOUBLE).i32Const(incl);
                fe.emitAs(r.start(), PrimitiveKind.DOUBLE);
                fe.emitAs(r.end(), PrimitiveKind.DOUBLE);
                c.structNew(ts.doubleRangeType);
            }
            default -> {
                int s = ctx.newLocal("$rs", WasmValType.refNull(ts.stringType));
                int e = ctx.newLocal("$re", WasmValType.refNull(ts.stringType));
                fe.emitBoxed(r.start());
                c.refCast(ts.stringType).localSet(s);
                fe.emitBoxed(r.end());
                c.refCast(ts.stringType).localSet(e);
                c.i32Const(WasmRangeOpsGen.KIND_STRING)
                        .i32Const(incl)
                        .localGet(s)
                        .refAsNonNull()
                        .localGet(e)
                        .refAsNonNull()
                        .structNew(ts.stringRangeType);
            }
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- BIP013 For loops

    public void emitFor(HirExpr.For f) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        PrimitiveKind resKind = f.meta().kind();
        int line = f.meta().line();
        LoweringTargets.ForKind kind =
                f.target() != null ? f.target().kind() : LoweringTargets.ForKind.DYNAMIC_UNION;

        if (kind == LoweringTargets.ForKind.COUNTED_RANGE && f.countedRange() != null) {
            emitCountedRangeFor(f, resKind, line);
            return;
        }

        // Evaluate iterable once
        fe.emitBoxed(f.iterable());
        int srcLocal = ctx.newLocal("$forSrc", ts.anyRef());
        c.localTee(srcLocal).refIsNull().if_();
        rt.emitStringLit(c, "Cannot iterate over null");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();

        // Dispatch fast path for ARRAY when statically known, or runtime-checked
        // array/collection/Babble iterable
        int resLocal = ctx.newLocal("$forRes", ts.valTypeFor(resKind));
        ts.emitDefaultValue(c, ts.valTypeFor(resKind));
        c.localSet(resLocal);

        int breakDepth = ctx.pushBlock("for_brk");
        c.block(ts.valTypeFor(resKind));

        if (kind == LoweringTargets.ForKind.ARRAY) {
            emitForOverArray(f, srcLocal, breakDepth, resKind, resLocal, line);
        } else if (kind == LoweringTargets.ForKind.JAVA_ITERATOR) {
            emitForOverIterator(f, srcLocal, breakDepth, resKind, resLocal, line, false);
        } else if (kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
            emitForOverIterator(f, srcLocal, breakDepth, resKind, resLocal, line, true);
        } else {
            // JAVA_ITERABLE, BABBLE_ITERABLE, BABBLE_TO_JAVA_ITERABLE, DYNAMIC_UNION:
            // Fast path for $List, $Set, $RangeBase, $CollectionIter, $RangeIter, $ArrayBase, else
            // call src.iterator()
            int itLocal = ctx.newLocal("$forIt", ts.anyRef());
            c.localGet(srcLocal).refTest(ts.listType).if_();
            ctx.pushBlock("for_is_list");
            c.localGet(srcLocal).refCast(ts.listType).call(cols.fnListIter).localSet(itLocal);
            c.else_();
            c.localGet(srcLocal).refTest(ts.setType).if_();
            ctx.pushBlock("for_is_set");
            c.localGet(srcLocal).refCast(ts.setType).call(cols.fnSetIter).localSet(itLocal);
            c.else_();
            c.localGet(srcLocal).refTest(ts.rangeBaseType).if_();
            ctx.pushBlock("for_is_range");
            c.localGet(srcLocal)
                    .refCast(ts.rangeBaseType)
                    .i32Const(line)
                    .call(ranges.fnRangeIter)
                    .localSet(itLocal);
            c.else_();
            c.localGet(srcLocal)
                    .refTest(ts.collectionIterType)
                    .localGet(srcLocal)
                    .refTest(ts.rangeIterType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_();
            ctx.pushBlock("for_is_iter");
            c.localGet(srcLocal).localSet(itLocal);
            c.else_();
            c.localGet(srcLocal).refTest(ts.arrayBaseType).if_();
            ctx.pushBlock("for_is_arr");
            var savedVars = new java.util.HashMap<>(ctx.vars);
            emitForOverArray(f, srcLocal, breakDepth, resKind, resLocal, line);
            ctx.vars.clear();
            ctx.vars.putAll(savedVars);
            c.localGet(resLocal).br(ctx.relDepth(breakDepth));
            c.else_();
            // Babble iterable: call src.iterator() via VTable getMemberFn or direct method
            emitCallZeroArgMember(srcLocal, "iterator", line);
            c.localSet(itLocal);
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();

            emitForOverIterator(
                    f,
                    itLocal,
                    breakDepth,
                    resKind,
                    resLocal,
                    line,
                    kind == LoweringTargets.ForKind.BABBLE_ITERABLE);
        }

        c.localGet(resLocal);
        ctx.popBlock();
        c.end(); // end for_brk
        ctx.stackKind = resKind;
    }

    private void emitCountedRangeFor(HirExpr.For f, PrimitiveKind resKind, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        HirExpr.CountedRangeLoop cr = f.countedRange();
        PrimitiveKind rk = cr.elemKind();
        boolean isLong = rk == PrimitiveKind.LONG;
        WasmValType loopType = isLong ? WasmValType.I64 : WasmValType.I32;
        PrimitiveKind loopKind = isLong ? PrimitiveKind.LONG : PrimitiveKind.INT;

        int startLocal = ctx.newLocal("$rStart", loopType);
        int endLocal = ctx.newLocal("$rEnd", loopType);
        fe.emitAs(cr.start(), rk);
        c.localSet(startLocal);
        fe.emitAs(cr.end(), rk);
        c.localSet(endLocal);

        long constStep = -1L;
        int stepLocal = -1;
        if (cr.step() == null) {
            constStep = 1L;
        } else if (cr.step() instanceof HirExpr.Const sc
                && sc.value() instanceof Number sn
                && sn.longValue() > 0L) {
            constStep = isLong ? sn.longValue() : sn.intValue();
        } else {
            stepLocal = ctx.newLocal("$rStep", loopType);
            fe.emitAs(cr.step(), loopKind);
            c.i32Const(line)
                    .call(isLong ? ranges.fnValidateStepLong : ranges.fnValidateStepInt)
                    .localSet(stepLocal);
        }

        int resLocal = ctx.newLocal("$forRes", ts.valTypeFor(resKind));
        ts.emitDefaultValue(c, ts.valTypeFor(resKind));
        c.localSet(resLocal);

        int breakDepth = ctx.pushBlock("for_brk");
        c.block(ts.valTypeFor(resKind));

        // Empty range guard
        c.localGet(startLocal).localGet(endLocal);
        if (isLong) {
            c.op(cr.inclusive() ? WasmInstr.SimpleOp.I64_LE_S : WasmInstr.SimpleOp.I64_LT_S);
        } else {
            c.op(cr.inclusive() ? WasmInstr.SimpleOp.I32_LE_S : WasmInstr.SimpleOp.I32_LT_S);
        }
        c.if_();
        ctx.pushBlock("for_rng_nonempty");

        int lastLocal = ctx.newLocal("$rLast", loopType);
        if (constStep == 1L) {
            if (cr.inclusive()) {
                c.localGet(endLocal).localSet(lastLocal);
            } else if (isLong) {
                c.localGet(endLocal)
                        .i64Const(1L)
                        .op(WasmInstr.SimpleOp.I64_SUB)
                        .localSet(lastLocal);
            } else {
                c.localGet(endLocal).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(lastLocal);
            }
        } else {
            c.localGet(startLocal).localGet(endLocal).i32Const(cr.inclusive() ? 1 : 0);
            if (stepLocal >= 0) {
                c.localGet(stepLocal);
            } else if (isLong) {
                c.i64Const(constStep);
            } else {
                c.i32Const((int) constStep);
            }
            c.call(isLong ? ranges.fnLastLong : ranges.fnLastInt).localSet(lastLocal);
        }

        int idxLocal = ctx.newLocal("$rIdx", loopType);
        int stopLocal = cr.descending() ? startLocal : lastLocal;
        c.localGet(cr.descending() ? lastLocal : startLocal).localSet(idxLocal);

        int loopDepth = ctx.pushBlock("for_rng_loop");
        c.loop();

        int contStepDepth = ctx.pushBlock("for_rng_cont");
        c.block();

        emitBindForLoopVar(
                f,
                () -> {
                    c.localGet(idxLocal);
                    ctx.stackKind = rk;
                },
                line);

        ctx.loops.push(new WasmFuncEmitter.LoopFrame(breakDepth, contStepDepth, resKind));
        try {
            emitForBody(f, resLocal, resKind);
        } finally {
            ctx.loops.pop();
        }

        ctx.popBlock();
        c.end(); // end for_rng_cont

        // Exit if idx == stop (overflow-safe)
        c.localGet(idxLocal)
                .localGet(stopLocal)
                .op(isLong ? WasmInstr.SimpleOp.I64_NE : WasmInstr.SimpleOp.I32_NE)
                .if_();
        ctx.pushBlock("for_rng_step");
        c.localGet(idxLocal);
        if (stepLocal >= 0) {
            c.localGet(stepLocal);
        } else if (isLong) {
            c.i64Const(constStep);
        } else {
            c.i32Const((int) constStep);
        }
        if (isLong) {
            c.op(cr.descending() ? WasmInstr.SimpleOp.I64_SUB : WasmInstr.SimpleOp.I64_ADD);
        } else {
            c.op(cr.descending() ? WasmInstr.SimpleOp.I32_SUB : WasmInstr.SimpleOp.I32_ADD);
        }
        c.localSet(idxLocal);
        c.br(ctx.relDepth(loopDepth));
        ctx.popBlock();
        c.end(); // end step if

        ctx.popBlock();
        c.end(); // end loop

        ctx.popBlock();
        c.end(); // end nonempty if

        c.localGet(resLocal);
        ctx.popBlock();
        c.end(); // end for_brk
        ctx.stackKind = resKind;
    }

    private void emitForOverArray(
            HirExpr.For f,
            int arrAnyLocal,
            int breakDepth,
            PrimitiveKind resKind,
            int resLocal,
            int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int idxLocal = ctx.newLocal("$forIdx", WasmValType.I32);
        int lenLocal = ctx.newLocal("$forLen", WasmValType.I32);

        // Compute length across all array wrapper types
        c.localGet(arrAnyLocal)
                .refCast(ts.arrayBaseType)
                .call(arrays.fnArraySize)
                .localSet(lenLocal);
        c.i32Const(0).localSet(idxLocal);

        int loopDepth = ctx.pushBlock("for_arr_loop");
        c.loop();
        c.localGet(idxLocal).localGet(lenLocal).op(WasmInstr.SimpleOp.I32_LT_S).if_();
        ctx.pushBlock("for_arr_if");

        // Prepare loop body continue target: continue increments idx and loops back!
        int contStepDepth = ctx.pushBlock("for_arr_cont");
        c.block();

        // Bind element into f.loopVar()
        emitBindForLoopVar(
                f,
                () -> {
                    PrimitiveKind vk = f.loopVar().primitiveKind();
                    if (vk == PrimitiveKind.INT) {
                        c.localGet(arrAnyLocal)
                                .refTest(ts.intArrayType)
                                .if_(WasmValType.I32)
                                .localGet(arrAnyLocal)
                                .refCast(ts.intArrayType)
                                .structGet(ts.intArrayType, 1)
                                .localGet(idxLocal)
                                .arrayGet(ts.i32ArrType)
                                .else_()
                                .localGet(arrAnyLocal)
                                .refCast(ts.arrayBaseType)
                                .localGet(idxLocal)
                                .i32Const(line)
                                .call(arrays.fnArrayGetBoxed)
                                .i32Const(line)
                                .call(rt.fnUnboxInt)
                                .end();
                        ctx.stackKind = PrimitiveKind.INT;
                    } else if (vk == PrimitiveKind.DOUBLE) {
                        c.localGet(arrAnyLocal)
                                .refTest(ts.doubleArrayType)
                                .if_(WasmValType.F64)
                                .localGet(arrAnyLocal)
                                .refCast(ts.doubleArrayType)
                                .structGet(ts.doubleArrayType, 1)
                                .localGet(idxLocal)
                                .arrayGet(ts.f64ArrType)
                                .else_()
                                .localGet(arrAnyLocal)
                                .refCast(ts.arrayBaseType)
                                .localGet(idxLocal)
                                .i32Const(line)
                                .call(arrays.fnArrayGetBoxed)
                                .i32Const(line)
                                .call(rt.fnUnboxDouble)
                                .end();
                        ctx.stackKind = PrimitiveKind.DOUBLE;
                    } else {
                        c.localGet(arrAnyLocal)
                                .refCast(ts.arrayBaseType)
                                .localGet(idxLocal)
                                .i32Const(line)
                                .call(arrays.fnArrayGetBoxed);
                        ctx.stackKind = PrimitiveKind.NONE;
                    }
                },
                line);

        ctx.loops.push(new WasmFuncEmitter.LoopFrame(breakDepth, contStepDepth, resKind));
        try {
            emitForBody(f, resLocal, resKind);
        } finally {
            ctx.loops.pop();
        }

        ctx.popBlock();
        c.end(); // end for_arr_cont
        c.localGet(idxLocal).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(idxLocal);
        c.br(ctx.relDepth(loopDepth));

        ctx.popBlock();
        c.end(); // end if
        ctx.popBlock();
        c.end(); // end loop
    }

    private void emitForOverIterator(
            HirExpr.For f,
            int itLocal,
            int breakDepth,
            PrimitiveKind resKind,
            int resLocal,
            int line,
            boolean preferBabble) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;

        int loopDepth = ctx.pushBlock("for_it_loop");
        c.loop();

        // Check hasNext() — handle $CollectionIter, $RangeIter, and Babble $Object iterator!
        c.localGet(itLocal)
                .refTest(ts.collectionIterType)
                .if_(WasmValType.I32)
                .localGet(itLocal)
                .refCast(ts.collectionIterType)
                .call(cols.fnIterHasNext)
                .else_()
                .localGet(itLocal)
                .refTest(ts.rangeIterType)
                .if_(WasmValType.I32)
                .localGet(itLocal)
                .refCast(ts.rangeIterType)
                .call(ranges.fnRangeIterHasNext)
                .else_();
        emitCallZeroArgMember(itLocal, "hasNext", line);
        c.call(rt.fnTruth);
        c.end().end();

        c.if_();
        ctx.pushBlock("for_it_if");

        // Bind element via next()
        emitBindForLoopVar(
                f,
                () -> {
                    c.localGet(itLocal)
                            .refTest(ts.collectionIterType)
                            .if_(ts.anyRef())
                            .localGet(itLocal)
                            .refCast(ts.collectionIterType)
                            .i32Const(line)
                            .call(cols.fnIterNext)
                            .else_()
                            .localGet(itLocal)
                            .refTest(ts.rangeIterType)
                            .if_(ts.anyRef())
                            .localGet(itLocal)
                            .refCast(ts.rangeIterType)
                            .i32Const(line)
                            .call(ranges.fnRangeIterNext)
                            .else_();
                    emitCallZeroArgMember(itLocal, "next", line);
                    c.end().end();
                    ctx.stackKind = PrimitiveKind.NONE;
                },
                line);

        ctx.loops.push(new WasmFuncEmitter.LoopFrame(breakDepth, loopDepth, resKind));
        try {
            emitForBody(f, resLocal, resKind);
        } finally {
            ctx.loops.pop();
        }
        c.br(ctx.relDepth(loopDepth));

        ctx.popBlock();
        c.end(); // end if
        ctx.popBlock();
        c.end(); // end loop
    }

    private void emitBindForLoopVar(HirExpr.For f, Runnable emitElement, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        // Allocate a fresh capture cell per iteration when loopVar is captured by a closure!
        HirVar lv = f.loopVar();
        if (lv.cell()) {
            ctx.vars.remove(lv.id());
        }
        fe.allocateLocalVar(lv);
        for (HirVar v : f.bodyDeclaredVars()) {
            if (v.id() != lv.id()) {
                if (v.cell()) {
                    ctx.vars.remove(v.id());
                }
                fe.allocateLocalVar(v);
            }
        }
        WasmFuncEmitter.VarSlot slot = ctx.vars.get(lv.id());
        emitElement.run();
        fe.convertStack(ctx.stackKind, slot.kind(), line);
        fe.storeVarSlotAndKeep(slot, true);
        ctx.code.drop();
    }

    private void emitForBody(HirExpr.For f, int resLocal, PrimitiveKind resKind) {
        fe.emitStmtList(f.body(), resKind);
        fe.ctx().code.localSet(resLocal);
    }

    /**
     * Calls a 0-argument method {@code memberName} on a Babble {@code $Object} stored in {@code
     * recvLocal}.
     */
    private void emitCallZeroArgMember(int recvLocal, String memberName, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int fnTmp = ctx.newLocal("$itFn", WasmValType.refNull(ts.fnBaseType));
        c.localGet(recvLocal).refTest(ts.objectType).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        rt.emitStringLit(c, "Expected iterable/iterator with ." + memberName + "()");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(recvLocal).refCast(ts.objectType);
        rt.emitStringLit(c, memberName);
        c.localGet(recvLocal)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 9)
                .refAsNonNull()
                .callRef(ts.getMemFnSigType)
                .refCast(ts.fnBaseType)
                .localTee(fnTmp)
                .refAsNonNull()
                .arrayNewFixed(ts.anyArrType, 0)
                .refNull(ts.anyArrType)
                .localGet(fnTmp)
                .refAsNonNull()
                .structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
    }

    // ---------------------------------------------------------------- CoreCollectionCall &
    // CoreCollectionRef

    public void emitCoreCollectionCall(HirExpr.CoreCollectionCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        String kind = call.collectionKind();
        List<HirCallArg> args = call.args();
        int line = call.meta().line();

        switch (kind) {
            case "list$reuse" -> {
                HirExpr fromArg = findNamedArg(args, "from");
                if (fromArg == null && !args.isEmpty()) fromArg = args.get(0).value();
                if (fromArg != null) {
                    fe.emitBoxed(fromArg);
                    int tmp = ctx.newLocal("$reuseL", ts.anyRef());
                    c.localTee(tmp)
                            .refTest(ts.listType)
                            .if_(WasmValType.I32)
                            .localGet(tmp)
                            .refCast(ts.listType)
                            .structGet(ts.listType, 2)
                            .refIsNull()
                            .else_()
                            .i32Const(0)
                            .end()
                            .if_(ts.anyRef())
                            .localGet(tmp)
                            .else_();
                    int l = ctx.newLocal("$newL", WasmValType.ref(ts.listType));
                    c.i32Const(4)
                            .call(cols.fnListNew)
                            .localTee(l)
                            .localGet(tmp)
                            .i32Const(line)
                            .call(cols.fnListAddAll)
                            .drop()
                            .localGet(l)
                            .end();
                } else {
                    c.i32Const(4).call(cols.fnListNew);
                }
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case "set$reuse" -> {
                HirExpr fromArg = findNamedArg(args, "from");
                if (fromArg == null && !args.isEmpty()) fromArg = args.get(0).value();
                if (fromArg != null) {
                    fe.emitBoxed(fromArg);
                    int tmp = ctx.newLocal("$reuseS", ts.anyRef());
                    c.localTee(tmp)
                            .refTest(ts.setType)
                            .if_(WasmValType.I32)
                            .localGet(tmp)
                            .refCast(ts.setType)
                            .structGet(ts.setType, 2)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .localGet(tmp)
                            .refCast(ts.setType)
                            .structGet(ts.setType, 3)
                            .refIsNull()
                            .op(WasmInstr.SimpleOp.I32_AND)
                            .else_()
                            .i32Const(0)
                            .end()
                            .if_(ts.anyRef())
                            .localGet(tmp)
                            .else_();
                    int s = ctx.newLocal("$newS", WasmValType.ref(ts.setType));
                    c.i32Const(0)
                            .call(cols.fnSetNew)
                            .localTee(s)
                            .localGet(tmp)
                            .i32Const(line)
                            .call(cols.fnSetAddAll)
                            .drop()
                            .localGet(s)
                            .end();
                } else {
                    c.i32Const(0).call(cols.fnSetNew);
                }
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case "map$reuse" -> {
                HirExpr fromArg = findNamedArg(args, "from");
                if (fromArg == null && !args.isEmpty()) fromArg = args.get(0).value();
                if (fromArg != null) {
                    fe.emitBoxed(fromArg);
                    int tmp = ctx.newLocal("$reuseM", ts.anyRef());
                    c.localTee(tmp)
                            .refTest(ts.mapType)
                            .if_(WasmValType.I32)
                            .localGet(tmp)
                            .refCast(ts.mapType)
                            .structGet(ts.mapType, 3)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .else_()
                            .i32Const(0)
                            .end()
                            .if_(ts.anyRef())
                            .localGet(tmp)
                            .else_();
                    int m = ctx.newLocal("$newM", WasmValType.ref(ts.mapType));
                    c.i32Const(0)
                            .call(cols.fnMapNew)
                            .localTee(m)
                            .localGet(tmp)
                            .i32Const(line)
                            .call(cols.fnMapPutAll)
                            .localGet(m)
                            .end();
                } else {
                    c.i32Const(0).call(cols.fnMapNew);
                }
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case "list" -> {
                HirExpr fromArg = findNamedArg(args, "from");
                HirExpr capArg = findNamedArg(args, "capacity");
                if (fromArg != null) {
                    int l = ctx.newLocal("$newL", WasmValType.ref(ts.listType));
                    c.i32Const(4).call(cols.fnListNew).localTee(l);
                    fe.emitBoxed(fromArg);
                    c.i32Const(line).call(cols.fnListAddAll).drop();
                    c.localGet(l);
                } else if (capArg != null) {
                    fe.emitAs(capArg, PrimitiveKind.INT);
                    int cap = ctx.newLocal("$cap", WasmValType.I32);
                    c.localTee(cap).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_();
                    rt.emitStringLit(c, "java.lang.IllegalArgumentException : Illegal Capacity: ");
                    c.localGet(cap)
                            .call(rt.fnIntToStr)
                            .call(rt.fnStrConcat)
                            .i32Const(line)
                            .call(rt.fnThrowMsg)
                            .unreachable()
                            .end();
                    c.localGet(cap).call(cols.fnListNew);
                } else {
                    int l = ctx.newLocal("$newL", WasmValType.ref(ts.listType));
                    c.i32Const(Math.max(4, args.size())).call(cols.fnListNew).localSet(l);
                    for (HirCallArg a : args) {
                        c.localGet(l);
                        fe.emitBoxed(a.value());
                        c.call(cols.fnListAdd).drop();
                    }
                    c.localGet(l);
                }
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case "set", "sortedset" -> {
                int sorted = "sortedset".equals(kind) ? 1 : 0;
                HirExpr fromArg = findNamedArg(args, "from");
                HirExpr capArg = findNamedArg(args, "capacity");
                if (capArg != null) {
                    fe.emitAs(capArg, PrimitiveKind.INT);
                    c.i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_();
                    rt.emitStringLit(c, "Illegal Capacity");
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
                    c.i32Const(sorted).call(cols.fnSetNew);
                } else if (fromArg != null) {
                    int s = ctx.newLocal("$newS", WasmValType.ref(ts.setType));
                    c.i32Const(sorted).call(cols.fnSetNew).localTee(s);
                    fe.emitBoxed(fromArg);
                    c.i32Const(line).call(cols.fnSetAddAll).drop();
                    c.localGet(s);
                } else {
                    int s = ctx.newLocal("$newS", WasmValType.ref(ts.setType));
                    c.i32Const(sorted).call(cols.fnSetNew).localSet(s);
                    for (HirCallArg a : args) {
                        c.localGet(s);
                        fe.emitBoxed(a.value());
                        c.i32Const(line).call(cols.fnSetAdd).drop();
                    }
                    c.localGet(s);
                }
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case "entry" -> {
                HirExpr keyArg = findNamedArg(args, "key");
                HirExpr valArg = findNamedArg(args, "value");
                if (keyArg == null && args.size() >= 1) keyArg = args.get(0).value();
                if (valArg == null && args.size() >= 2) valArg = args.get(1).value();
                if (keyArg != null) fe.emitBoxed(keyArg);
                else c.refNull(ts.anyType);
                if (valArg != null) fe.emitBoxed(valArg);
                else c.refNull(ts.anyType);
                c.call(cols.fnEntryNew);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case "map", "sortedmap" -> {
                int sorted = "sortedmap".equals(kind) ? 1 : 0;
                HirExpr fromArg = findNamedArg(args, "from");
                HirExpr capArg = findNamedArg(args, "capacity");
                if (capArg != null) {
                    fe.emitAs(capArg, PrimitiveKind.INT);
                    c.i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_();
                    rt.emitStringLit(c, "Illegal Capacity");
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
                    c.i32Const(sorted).call(cols.fnMapNew);
                } else if (fromArg != null) {
                    int m = ctx.newLocal("$newM", WasmValType.ref(ts.mapType));
                    c.i32Const(sorted).call(cols.fnMapNew).localTee(m);
                    fe.emitBoxed(fromArg);
                    c.i32Const(line).call(cols.fnMapPutAll);
                    c.localGet(m);
                } else {
                    int m = ctx.newLocal("$newM", WasmValType.ref(ts.mapType));
                    c.i32Const(sorted).call(cols.fnMapNew).localSet(m);
                    int eTmp = ctx.newLocal("$mapEnt", ts.anyRef());
                    for (HirCallArg a : args) {
                        fe.emitBoxed(a.value());
                        c.localSet(eTmp);
                        c.localGet(m)
                                .localGet(eTmp)
                                .refCast(ts.entryType)
                                .structGet(ts.entryType, 0)
                                .localGet(eTmp)
                                .refCast(ts.entryType)
                                .structGet(ts.entryType, 1)
                                .i32Const(line)
                                .call(cols.fnMapPut)
                                .drop();
                    }
                    c.localGet(m);
                }
                ctx.stackKind = PrimitiveKind.NONE;
            }
            default -> {
                rt.emitStringLit(c, "Unknown core collection : " + kind);
                c.i32Const(line).call(rt.fnThrowMsg).unreachable();
                ctx.stackKind = PrimitiveKind.NONE;
            }
        }
    }

    /**
     * Emits {@code list.of(...)}, {@code set.of(...)}, {@code map.of(...)}, {@code copyOf(...)},
     * etc.
     */
    public void emitCollectionFactoryCall(
            String kind, String method, List<HirCallArg> args, int line) {
        if (("map".equals(kind) || "sortedmap".equals(kind)) && "of".equals(method)) {
            WasmFuncEmitter.EmitCtx ctx = fe.ctx();
            WasmCodeBuilder c = ctx.code;
            int sorted = "sortedmap".equals(kind) ? 1 : 0;
            int m = ctx.newLocal("$mapOf", WasmValType.ref(ts.mapType));
            c.i32Const(sorted).call(cols.fnMapNew).localSet(m);
            for (int i = 0; i + 1 < args.size(); i += 2) {
                c.localGet(m);
                fe.emitBoxed(args.get(i).value());
                fe.emitBoxed(args.get(i + 1).value());
                c.i32Const(line).call(cols.fnMapPut).drop();
            }
            c.localGet(m);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        List<HirCallArg> effectiveArgs = args;
        if ("copyOf".equals(method) && args.size() == 1 && args.get(0).name() == null) {
            effectiveArgs = List.of(new HirCallArg("from", args.get(0).value()));
        }
        emitCoreCollectionCall(
                new HirExpr.CoreCollectionCall(
                        kind,
                        effectiveArgs,
                        new org.babblelang.ir.hir.ExprMeta(
                                line, PrimitiveKind.NONE, 0, null, null, false, false)));
    }

    public void emitCoreCollectionRef(HirExpr.CoreCollectionRef ref) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        int g = linker.ops().builtinCallableGlobal("col_" + ref.collectionKind());
        ctx.code.globalGet(g).refAsNonNull();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private static HirExpr findNamedArg(List<HirCallArg> args, String name) {
        for (HirCallArg a : args) {
            if (name.equals(a.name())) {
                return a.value();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Portable JavaFieldGet
    // (Math/primitive constants)

    public void emitJavaFieldGet(HirExpr.JavaFieldGet fg) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        String owner = fg.target().field().getDeclaringClass().getName();
        String name = fg.target().field().getName();
        if ("java.lang.Math".equals(owner)) {
            if ("PI".equals(name)) {
                c.f64Const(Math.PI);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
            if ("E".equals(name)) {
                c.f64Const(Math.E);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
        }
        if ("java.lang.Integer".equals(owner)) {
            if ("MAX_VALUE".equals(name)) {
                c.i32Const(Integer.MAX_VALUE);
                ctx.stackKind = PrimitiveKind.INT;
                return;
            }
            if ("MIN_VALUE".equals(name)) {
                c.i32Const(Integer.MIN_VALUE);
                ctx.stackKind = PrimitiveKind.INT;
                return;
            }
        }
        if ("java.lang.Long".equals(owner)) {
            if ("MAX_VALUE".equals(name)) {
                c.i64Const(Long.MAX_VALUE);
                ctx.stackKind = PrimitiveKind.LONG;
                return;
            }
            if ("MIN_VALUE".equals(name)) {
                c.i64Const(Long.MIN_VALUE);
                ctx.stackKind = PrimitiveKind.LONG;
                return;
            }
        }
        if ("java.lang.Double".equals(owner)) {
            if ("MAX_VALUE".equals(name)) {
                c.f64Const(Double.MAX_VALUE);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
            if ("MIN_VALUE".equals(name)) {
                c.f64Const(Double.MIN_VALUE);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
            if ("POSITIVE_INFINITY".equals(name)) {
                c.f64Const(Double.POSITIVE_INFINITY);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
            if ("NEGATIVE_INFINITY".equals(name)) {
                c.f64Const(Double.NEGATIVE_INFINITY);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
            if ("NaN".equals(name)) {
                c.f64Const(Double.NaN);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
        }
        if ("java.lang.Float".equals(owner)) {
            if ("MAX_VALUE".equals(name)) {
                c.f32Const(Float.MAX_VALUE);
                ctx.stackKind = PrimitiveKind.FLOAT;
                return;
            }
            if ("MIN_VALUE".equals(name)) {
                c.f32Const(Float.MIN_VALUE);
                ctx.stackKind = PrimitiveKind.FLOAT;
                return;
            }
            if ("POSITIVE_INFINITY".equals(name)) {
                c.f32Const(Float.POSITIVE_INFINITY);
                ctx.stackKind = PrimitiveKind.FLOAT;
                return;
            }
            if ("NEGATIVE_INFINITY".equals(name)) {
                c.f32Const(Float.NEGATIVE_INFINITY);
                ctx.stackKind = PrimitiveKind.FLOAT;
                return;
            }
            if ("NaN".equals(name)) {
                c.f32Const(Float.NaN);
                ctx.stackKind = PrimitiveKind.FLOAT;
                return;
            }
        }
        if ("java.lang.Short".equals(owner)) {
            if ("MAX_VALUE".equals(name)) {
                c.i32Const(Short.MAX_VALUE);
                ctx.stackKind = PrimitiveKind.SHORT;
                return;
            }
            if ("MIN_VALUE".equals(name)) {
                c.i32Const(Short.MIN_VALUE);
                ctx.stackKind = PrimitiveKind.SHORT;
                return;
            }
        }
        if ("java.lang.Byte".equals(owner)) {
            if ("MAX_VALUE".equals(name)) {
                c.i32Const(Byte.MAX_VALUE);
                ctx.stackKind = PrimitiveKind.BYTE;
                return;
            }
            if ("MIN_VALUE".equals(name)) {
                c.i32Const(Byte.MIN_VALUE);
                ctx.stackKind = PrimitiveKind.BYTE;
                return;
            }
        }
        rt.emitStringLit(c, "Not supported on target wasm : " + owner + "." + name);
        c.i32Const(fg.meta().line()).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- Portable CoreMethodCall &
    // JavaMethodCall

    public void emitCoreMethodCall(HirExpr.CoreMethodCall call) {
        if (dispatchCoreMethod(
                call.coreKind(),
                call.memberName(),
                call.receiver(),
                call.args(),
                call.meta().kind(),
                call.meta().line())) {
            return;
        }
        WasmCodeBuilder c = fe.ctx().code;
        rt.emitStringLit(
                c, "Unsupported core method : " + call.coreKind() + "." + call.memberName());
        c.i32Const(call.meta().line()).call(rt.fnThrowMsg).unreachable();
        fe.ctx().stackKind = PrimitiveKind.NONE;
    }

    public void emitPortableJavaMethodCall(HirExpr.JavaMethodCall call) {
        String owner = call.target().method().getDeclaringClass().getName();
        String name = call.target().method().getName();
        int line = call.meta().line();
        // Static portable collection factories: never evaluate pure static receiver (e.g.
        // java.util.List)
        if (("java.util.List".equals(owner)
                        || "java.util.Set".equals(owner)
                        || "java.util.Map".equals(owner))
                && ("of".equals(name)
                        || "copyOf".equals(name)
                        || "ofEntries".equals(name)
                        || "entry".equals(name))) {
            if (!call.pureStaticReceiver() && call.receiver() != null) {
                fe.emitVoid(call.receiver());
            }
            if ("entry".equals(name) && call.args().size() == 2) {
                fe.emitBoxed(call.args().get(0));
                fe.emitBoxed(call.args().get(1));
                fe.ctx().code.call(cols.fnEntryNew);
                fe.ctx().stackKind = PrimitiveKind.NONE;
                return;
            }
            String colKind =
                    "java.util.List".equals(owner)
                            ? "list"
                            : "java.util.Set".equals(owner) ? "set" : "map";
            List<HirCallArg> cargs = new java.util.ArrayList<>(call.args().size());
            for (HirExpr a : call.args()) {
                cargs.add(new HirCallArg(null, a));
            }
            emitCollectionFactoryCall(colKind, name, cargs, line);
            return;
        }
        String coreKind = mapJavaOwnerToCoreKind(owner);
        if (coreKind != null
                && dispatchCoreMethod(
                        coreKind, name, call.receiver(), call.args(), call.meta().kind(), line)) {
            return;
        }
        if ("java.lang.Math".equals(owner) || "java.lang.StrictMath".equals(owner)) {
            if (!call.pureStaticReceiver() && call.receiver() != null) {
                fe.emitVoid(call.receiver());
            }
            if (emitMathMethod(
                    name, call.target().method(), call.args(), call.meta().kind(), line)) {
                return;
            }
        }
        if ("java.lang.Object".equals(owner)
                && emitObjectMethod(name, call.receiver(), call.args(), line)) {
            return;
        }
        WasmCodeBuilder c = fe.ctx().code;
        rt.emitStringLit(c, "Not supported on target wasm : " + owner + "." + name);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        fe.ctx().stackKind = PrimitiveKind.NONE;
    }

    private static String mapJavaOwnerToCoreKind(String owner) {
        return switch (owner) {
            case "org.babblelang.runtime.BabbleException", "java.lang.Throwable" -> "exception";
            case "java.lang.String", "java.lang.CharSequence" -> "string";
            case "java.util.List", "java.util.ArrayList" -> "list";
            case "java.util.Set",
                    "java.util.SortedSet",
                    "java.util.NavigableSet",
                    "java.util.LinkedHashSet",
                    "java.util.TreeSet",
                    "java.util.HashSet" ->
                    "set";
            case "java.util.Collection", "java.lang.Iterable" -> "collection";
            case "java.util.Map",
                    "java.util.SortedMap",
                    "java.util.NavigableMap",
                    "java.util.LinkedHashMap",
                    "java.util.TreeMap",
                    "java.util.HashMap" ->
                    "map";
            case "java.util.Map$Entry", "java.util.AbstractMap$SimpleImmutableEntry" -> "entry";
            case "java.util.Iterator" -> "iterator";
            case "java.lang.Appendable", "java.io.Writer", "java.io.PrintWriter" -> "appendable";
            case "org.babblelang.runtime.Range",
                    "org.babblelang.runtime.IntRange",
                    "org.babblelang.runtime.LongRange",
                    "org.babblelang.runtime.CharRange",
                    "org.babblelang.runtime.DoubleRange",
                    "org.babblelang.runtime.ComparableRange" ->
                    "range";
            default -> null;
        };
    }

    private boolean dispatchCoreMethod(
            String kind,
            String name,
            HirExpr recv,
            List<HirExpr> args,
            PrimitiveKind expectedKind,
            int line) {
        return switch (kind) {
            case "exception" -> emitExceptionMethod(name, recv, args);
            case "string" -> emitStringMethod(name, recv, args, line);
            case "list" -> emitListMethod(name, recv, args, line);
            case "set", "sortedset" -> emitSetMethod(name, recv, args, line);
            case "collection" -> emitCollectionMethod(name, recv, args, line);
            case "map", "sortedmap" -> emitMapMethod(name, recv, args, line);
            case "entry" -> emitEntryMethod(name, recv, args, line);
            case "iterator" -> emitIteratorMethod(name, recv, line);
            case "appendable" -> emitAppendableMethod(name, recv, args, line);
            case "range" -> emitRangeMethod(name, recv, args, line);
            default -> false;
        };
    }

    private boolean emitRangeMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        switch (name) {
            case "start" -> {
                c.refCast(ts.rangeBaseType).call(ranges.fnRangeStart);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "end" -> {
                c.refCast(ts.rangeBaseType).call(ranges.fnRangeEnd);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "startInt" -> {
                c.refCast(ts.intRangeType).structGet(ts.intRangeType, 2);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "endInt" -> {
                c.refCast(ts.intRangeType).structGet(ts.intRangeType, 3);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "startChar" -> {
                c.refCast(ts.intRangeType).structGet(ts.intRangeType, 2);
                ctx.stackKind = PrimitiveKind.CHAR;
                return true;
            }
            case "endChar" -> {
                c.refCast(ts.intRangeType).structGet(ts.intRangeType, 3);
                ctx.stackKind = PrimitiveKind.CHAR;
                return true;
            }
            case "startLong" -> {
                c.refCast(ts.longRangeType).structGet(ts.longRangeType, 2);
                ctx.stackKind = PrimitiveKind.LONG;
                return true;
            }
            case "endLong" -> {
                c.refCast(ts.longRangeType).structGet(ts.longRangeType, 3);
                ctx.stackKind = PrimitiveKind.LONG;
                return true;
            }
            case "startDouble" -> {
                c.refCast(ts.doubleRangeType).structGet(ts.doubleRangeType, 2);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return true;
            }
            case "endDouble" -> {
                c.refCast(ts.doubleRangeType).structGet(ts.doubleRangeType, 3);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return true;
            }
            case "inclusive" -> {
                c.refCast(ts.rangeBaseType).structGet(ts.rangeBaseType, 1);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "isEmpty" -> {
                c.refCast(ts.rangeBaseType).call(ranges.fnRangeIsEmpty);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "containsInt" -> {
                c.refCast(ts.intRangeType);
                fe.emitAs(args.get(0), PrimitiveKind.INT);
                c.call(ranges.fnRangeContainsInt);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "containsChar" -> {
                c.refCast(ts.intRangeType);
                fe.emitAs(args.get(0), PrimitiveKind.CHAR);
                c.call(ranges.fnRangeContainsInt);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "containsLong" -> {
                c.refCast(ts.longRangeType);
                fe.emitAs(args.get(0), PrimitiveKind.LONG);
                c.call(ranges.fnRangeContainsLong);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "containsDouble" -> {
                c.refCast(ts.doubleRangeType);
                fe.emitAs(args.get(0), PrimitiveKind.DOUBLE);
                c.call(ranges.fnRangeContainsDouble);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "contains" -> {
                c.refCast(ts.rangeBaseType);
                fe.emitBoxed(args.get(0));
                c.call(ranges.fnRangeContainsBoxed);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "step" -> {
                c.refCast(ts.rangeBaseType);
                fe.emitBoxed(args.get(0));
                c.i32Const(line).call(ranges.fnRangeStep);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "reversed" -> {
                c.refCast(ts.rangeBaseType).i32Const(line).call(ranges.fnRangeReversed);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "iterator" -> {
                c.refCast(ts.rangeBaseType).i32Const(line).call(ranges.fnRangeIter);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "equals" -> {
                fe.emitBoxed(args.get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "hashCode" -> {
                c.call(rt.fnHashCode);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "toString" -> {
                c.call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            default -> {
                c.drop();
                return false;
            }
        }
    }

    private boolean emitExceptionMethod(String name, HirExpr recv, List<HirExpr> args) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        c.refCast(ts.babbleExnType);
        switch (name) {
            case "getMessage" -> {
                c.structGet(ts.babbleExnType, 0);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "getCause" -> {
                c.structGet(ts.babbleExnType, 1);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "addSuppressed" -> {
                if (args.size() == 1) {
                    fe.emitBoxed(args.get(0));
                    c.refAsNonNull().call(rt.fnAddSuppressed).refNull(ts.anyType);
                    ctx.stackKind = PrimitiveKind.NONE;
                    return true;
                }
            }
            case "getSuppressed" -> {
                int exn = ctx.newLocal("$exn", WasmValType.refNull(ts.babbleExnType));
                int cnt = ctx.newLocal("$scnt", WasmValType.I32);
                int out = ctx.newLocal("$sout", WasmValType.refNull(ts.anyArrType));
                c.localTee(exn)
                        .structGet(ts.babbleExnType, 3)
                        .localTee(cnt)
                        .arrayNewDefault(ts.anyArrType)
                        .localSet(out);
                c.localGet(cnt)
                        .i32Const(0)
                        .op(WasmInstr.SimpleOp.I32_GT_S)
                        .if_()
                        .localGet(out)
                        .refAsNonNull()
                        .i32Const(0)
                        .localGet(exn)
                        .structGet(ts.babbleExnType, 2)
                        .refAsNonNull()
                        .i32Const(0)
                        .localGet(cnt)
                        .arrayCopy(ts.anyArrType, ts.anyArrType)
                        .end();
                c.globalGet(arrays.gTdAny)
                        .refAsNonNull()
                        .localGet(out)
                        .refAsNonNull()
                        .structNew(ts.refArrayType);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "toString" -> {
                c.call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            default -> {}
        }
        return false;
    }

    private boolean emitStringMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        c.refCast(ts.stringType);
        switch (name) {
            case "length" -> {
                c.structGet(ts.stringType, 0).arrayLen();
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "isEmpty" -> {
                c.structGet(ts.stringType, 0).arrayLen().op(WasmInstr.SimpleOp.I32_EQZ);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "isBlank" -> {
                c.call(strings.fnIsBlank);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "charAt" -> {
                fe.emitAs(args.get(0), PrimitiveKind.INT);
                c.i32Const(line).call(strings.fnCharAt);
                ctx.stackKind = PrimitiveKind.CHAR;
                return true;
            }
            case "substring" -> {
                if (args.size() == 1) {
                    int s = ctx.newLocal("$subS", WasmValType.ref(ts.stringType));
                    c.localTee(s);
                    fe.emitAs(args.get(0), PrimitiveKind.INT);
                    c.localGet(s)
                            .structGet(ts.stringType, 0)
                            .arrayLen()
                            .i32Const(line)
                            .call(strings.fnSubstring);
                } else {
                    fe.emitAs(args.get(0), PrimitiveKind.INT);
                    fe.emitAs(args.get(1), PrimitiveKind.INT);
                    c.i32Const(line).call(strings.fnSubstring);
                }
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "indexOf" -> {
                HirExpr a0 = args.get(0);
                boolean isCharOrInt =
                        a0.meta().kind() == PrimitiveKind.CHAR
                                || a0.meta().kind() == PrimitiveKind.INT;
                if (isCharOrInt) {
                    fe.emitAs(a0, PrimitiveKind.INT);
                    if (args.size() > 1) fe.emitAs(args.get(1), PrimitiveKind.INT);
                    else c.i32Const(0);
                    c.call(strings.fnIndexOfChar);
                } else {
                    fe.emitBoxed(a0);
                    c.refCast(ts.stringType);
                    if (args.size() > 1) fe.emitAs(args.get(1), PrimitiveKind.INT);
                    else c.i32Const(0);
                    c.call(strings.fnIndexOfStr);
                }
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "lastIndexOf" -> {
                HirExpr a0 = args.get(0);
                int s = ctx.newLocal("$liS", WasmValType.ref(ts.stringType));
                c.localTee(s);
                boolean isCharOrInt =
                        a0.meta().kind() == PrimitiveKind.CHAR
                                || a0.meta().kind() == PrimitiveKind.INT;
                if (isCharOrInt) {
                    fe.emitAs(a0, PrimitiveKind.INT);
                    if (args.size() > 1) fe.emitAs(args.get(1), PrimitiveKind.INT);
                    else c.localGet(s).structGet(ts.stringType, 0).arrayLen();
                    c.call(strings.fnLastIndexOfChar);
                } else {
                    fe.emitBoxed(a0);
                    c.refCast(ts.stringType);
                    if (args.size() > 1) fe.emitAs(args.get(1), PrimitiveKind.INT);
                    else c.localGet(s).structGet(ts.stringType, 0).arrayLen();
                    c.call(strings.fnLastIndexOfStr);
                }
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "startsWith" -> {
                fe.emitBoxed(args.get(0));
                c.refCast(ts.stringType);
                if (args.size() > 1) fe.emitAs(args.get(1), PrimitiveKind.INT);
                else c.i32Const(0);
                c.call(strings.fnStartsWith);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "endsWith" -> {
                fe.emitBoxed(args.get(0));
                c.refCast(ts.stringType).call(strings.fnEndsWith);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "contains" -> {
                fe.emitBoxed(args.get(0));
                c.refCast(ts.stringType)
                        .i32Const(0)
                        .call(strings.fnIndexOfStr)
                        .i32Const(0)
                        .op(WasmInstr.SimpleOp.I32_GE_S);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "replace" -> {
                if (args.get(0).meta().kind() == PrimitiveKind.CHAR) {
                    fe.emitAs(args.get(0), PrimitiveKind.CHAR);
                    fe.emitAs(args.get(1), PrimitiveKind.CHAR);
                    c.call(strings.fnReplaceChar);
                } else {
                    fe.emitBoxed(args.get(0));
                    c.refCast(ts.stringType);
                    fe.emitBoxed(args.get(1));
                    c.refCast(ts.stringType);
                    c.call(strings.fnReplaceStr);
                }
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "trim", "strip" -> {
                c.call(strings.fnTrim);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "toLowerCase" -> {
                c.call(strings.fnToLower);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "toUpperCase" -> {
                c.call(strings.fnToUpper);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "equalsIgnoreCase" -> {
                fe.emitBoxed(args.get(0));
                c.call(strings.fnEqualsIgnoreCase);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "compareTo" -> {
                fe.emitBoxed(args.get(0));
                c.refCast(ts.stringType).call(strings.fnCompareTo);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "concat" -> {
                fe.emitBoxed(args.get(0));
                c.refCast(ts.stringType).call(rt.fnStrConcat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "repeat" -> {
                fe.emitAs(args.get(0), PrimitiveKind.INT);
                c.i32Const(line).call(strings.fnRepeat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "split" -> {
                fe.emitBoxed(args.get(0));
                c.refCast(ts.stringType);
                if (args.size() >= 2) {
                    fe.emitAs(args.get(1), PrimitiveKind.INT);
                } else {
                    c.i32Const(0);
                }
                c.call(strings.fnSplit);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "toCharArray" -> {
                c.call(strings.fnToCharArray);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "equals" -> {
                fe.emitBoxed(args.get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "hashCode" -> {
                c.call(rt.fnStrHash);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "toString" -> {
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            default -> {
                c.drop();
                return false;
            }
        }
    }

    private boolean emitListMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        c.refCast(ts.listType);
        switch (name) {
            case "size" -> {
                c.structGet(ts.listType, 1);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "isEmpty" -> {
                c.structGet(ts.listType, 1).op(WasmInstr.SimpleOp.I32_EQZ);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "add" -> {
                if (args.size() == 2) {
                    fe.emitAs(args.get(0), PrimitiveKind.INT);
                    fe.emitBoxed(args.get(1));
                    c.i32Const(line).call(cols.fnListAddAt).refNull(ts.anyType);
                    ctx.stackKind = PrimitiveKind.NONE;
                } else {
                    fe.emitBoxed(args.get(0));
                    c.call(cols.fnListAdd);
                    ctx.stackKind = PrimitiveKind.BOOL;
                }
                return true;
            }
            case "get" -> {
                fe.emitAs(args.get(0), PrimitiveKind.INT);
                c.i32Const(line).call(cols.fnListGet);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "getFirst" -> {
                c.i32Const(0).i32Const(line).call(cols.fnListGet);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "getLast" -> {
                int l = ctx.newLocal("$gl", WasmValType.ref(ts.listType));
                c.localTee(l)
                        .localGet(l)
                        .structGet(ts.listType, 1)
                        .i32Const(1)
                        .op(WasmInstr.SimpleOp.I32_SUB)
                        .i32Const(line)
                        .call(cols.fnListGet);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "set" -> {
                fe.emitAs(args.get(0), PrimitiveKind.INT);
                fe.emitBoxed(args.get(1));
                c.i32Const(line).call(cols.fnListSet);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "remove" -> {
                PrimitiveKind ak = args.get(0).meta().kind();
                if (ak == PrimitiveKind.INT
                        || ak == PrimitiveKind.BYTE
                        || ak == PrimitiveKind.SHORT) {
                    fe.emitAs(args.get(0), PrimitiveKind.INT);
                    c.i32Const(line).call(cols.fnListRemoveAt);
                    ctx.stackKind = PrimitiveKind.NONE;
                } else {
                    fe.emitBoxed(args.get(0));
                    c.call(cols.fnListRemoveVal);
                    ctx.stackKind = PrimitiveKind.BOOL;
                }
                return true;
            }
            case "indexOf" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnListIndexOf);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "lastIndexOf" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnListLastIndexOf);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "contains" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnListContains);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "clear" -> {
                c.call(cols.fnListClear).refNull(ts.anyType);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "addAll" -> {
                fe.emitBoxed(args.get(0));
                c.i32Const(line).call(cols.fnListAddAll);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "subList" -> {
                fe.emitAs(args.get(0), PrimitiveKind.INT);
                fe.emitAs(args.get(1), PrimitiveKind.INT);
                c.i32Const(line).call(cols.fnListSubList);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "iterator" -> {
                c.call(cols.fnListIter);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "equals" -> {
                fe.emitBoxed(args.get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "hashCode" -> {
                c.call(rt.fnHashCode);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "toString" -> {
                c.call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            default -> {
                c.drop();
                return false;
            }
        }
    }

    private boolean emitSetMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        c.refCast(ts.setType);
        switch (name) {
            case "size" -> {
                c.structGet(ts.setType, 1);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "isEmpty" -> {
                c.structGet(ts.setType, 1).op(WasmInstr.SimpleOp.I32_EQZ);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "add" -> {
                fe.emitBoxed(args.get(0));
                c.i32Const(line).call(cols.fnSetAdd);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "contains" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnSetContains);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "remove" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnSetRemove);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "clear" -> {
                c.call(cols.fnSetClear).refNull(ts.anyType);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "first", "getFirst" -> {
                c.i32Const(line).call(cols.fnSetFirst);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "last", "getLast" -> {
                c.i32Const(line).call(cols.fnSetLast);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "addAll" -> {
                fe.emitBoxed(args.get(0));
                c.i32Const(line).call(cols.fnSetAddAll);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "iterator" -> {
                c.call(cols.fnSetIter);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "equals" -> {
                fe.emitBoxed(args.get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "hashCode" -> {
                c.call(rt.fnHashCode);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "toString" -> {
                c.call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            default -> {
                c.drop();
                return false;
            }
        }
    }

    private boolean emitCollectionMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        int col = ctx.newLocal("$colRecv", ts.anyRef());
        c.localSet(col);
        switch (name) {
            case "size" -> {
                c.localGet(col)
                        .refTest(ts.listType)
                        .if_(WasmValType.I32)
                        .localGet(col)
                        .refCast(ts.listType)
                        .structGet(ts.listType, 1)
                        .else_()
                        .localGet(col)
                        .refCast(ts.setType)
                        .structGet(ts.setType, 1)
                        .end();
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "isEmpty" -> {
                c.localGet(col)
                        .refTest(ts.listType)
                        .if_(WasmValType.I32)
                        .localGet(col)
                        .refCast(ts.listType)
                        .structGet(ts.listType, 1)
                        .op(WasmInstr.SimpleOp.I32_EQZ)
                        .else_()
                        .localGet(col)
                        .refCast(ts.setType)
                        .structGet(ts.setType, 1)
                        .op(WasmInstr.SimpleOp.I32_EQZ)
                        .end();
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "contains" -> {
                fe.emitBoxed(args.get(0));
                int v = ctx.newLocal("$cv", ts.anyRef());
                c.localSet(v);
                c.localGet(col)
                        .refTest(ts.listType)
                        .if_(WasmValType.I32)
                        .localGet(col)
                        .refCast(ts.listType)
                        .localGet(v)
                        .call(cols.fnListContains)
                        .else_()
                        .localGet(col)
                        .refCast(ts.setType)
                        .localGet(v)
                        .call(cols.fnSetContains)
                        .end();
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "add" -> {
                fe.emitBoxed(args.get(0));
                int v = ctx.newLocal("$cv", ts.anyRef());
                c.localSet(v);
                c.localGet(col)
                        .refTest(ts.listType)
                        .if_(WasmValType.I32)
                        .localGet(col)
                        .refCast(ts.listType)
                        .localGet(v)
                        .call(cols.fnListAdd)
                        .else_()
                        .localGet(col)
                        .refCast(ts.setType)
                        .localGet(v)
                        .i32Const(line)
                        .call(cols.fnSetAdd)
                        .end();
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "remove" -> {
                fe.emitBoxed(args.get(0));
                int v = ctx.newLocal("$cv", ts.anyRef());
                c.localSet(v);
                c.localGet(col)
                        .refTest(ts.listType)
                        .if_(WasmValType.I32)
                        .localGet(col)
                        .refCast(ts.listType)
                        .localGet(v)
                        .call(cols.fnListRemoveVal)
                        .else_()
                        .localGet(col)
                        .refCast(ts.setType)
                        .localGet(v)
                        .call(cols.fnSetRemove)
                        .end();
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "addAll" -> {
                fe.emitBoxed(args.get(0));
                int src = ctx.newLocal("$cSrc", ts.anyRef());
                c.localSet(src);
                c.localGet(col)
                        .refTest(ts.listType)
                        .if_(WasmValType.I32)
                        .localGet(col)
                        .refCast(ts.listType)
                        .localGet(src)
                        .i32Const(line)
                        .call(cols.fnListAddAll)
                        .else_()
                        .localGet(col)
                        .refCast(ts.setType)
                        .localGet(src)
                        .i32Const(line)
                        .call(cols.fnSetAddAll)
                        .end();
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "clear" -> {
                c.localGet(col)
                        .refTest(ts.listType)
                        .if_()
                        .localGet(col)
                        .refCast(ts.listType)
                        .call(cols.fnListClear)
                        .else_()
                        .localGet(col)
                        .refCast(ts.setType)
                        .call(cols.fnSetClear)
                        .end()
                        .refNull(ts.anyType);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "equals" -> {
                c.localGet(col);
                fe.emitBoxed(args.get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "hashCode" -> {
                c.localGet(col).call(rt.fnHashCode);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "toString" -> {
                c.localGet(col).call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "iterator" -> {
                c.localGet(col)
                        .refTest(ts.listType)
                        .if_(ts.anyRef())
                        .localGet(col)
                        .refCast(ts.listType)
                        .call(cols.fnListIter)
                        .else_()
                        .localGet(col)
                        .refTest(ts.setType)
                        .if_(ts.anyRef())
                        .localGet(col)
                        .refCast(ts.setType)
                        .call(cols.fnSetIter)
                        .else_()
                        .localGet(col)
                        .refTest(ts.rangeBaseType)
                        .if_(ts.anyRef())
                        .localGet(col)
                        .refCast(ts.rangeBaseType)
                        .i32Const(line)
                        .call(ranges.fnRangeIter)
                        .else_();
                emitCallZeroArgMember(col, "iterator", line);
                c.end().end().end();
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean emitMapMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        c.refCast(ts.mapType);
        switch (name) {
            case "size" -> {
                c.structGet(ts.mapType, 2);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "isEmpty" -> {
                c.structGet(ts.mapType, 2).op(WasmInstr.SimpleOp.I32_EQZ);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "put" -> {
                fe.emitBoxed(args.get(0));
                fe.emitBoxed(args.get(1));
                c.i32Const(line).call(cols.fnMapPut);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "get" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnMapGet);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "getOrDefault" -> {
                fe.emitBoxed(args.get(0));
                fe.emitBoxed(args.get(1));
                c.call(cols.fnMapGetOrDefault);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "containsKey" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnMapContainsKey);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "containsValue" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnMapContainsValue);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "remove" -> {
                fe.emitBoxed(args.get(0));
                c.call(cols.fnMapRemove);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "clear" -> {
                c.call(cols.fnMapClear).refNull(ts.anyType);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "keySet" -> {
                c.call(cols.fnMapKeySet);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "values" -> {
                c.call(cols.fnMapValues);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "entrySet" -> {
                c.call(cols.fnMapEntrySet);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "firstKey" -> {
                c.i32Const(line).call(cols.fnMapFirstKey);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "lastKey" -> {
                c.i32Const(line).call(cols.fnMapLastKey);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "putAll" -> {
                fe.emitBoxed(args.get(0));
                c.i32Const(line).call(cols.fnMapPutAll).refNull(ts.anyType);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "equals" -> {
                fe.emitBoxed(args.get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "hashCode" -> {
                c.call(rt.fnHashCode);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "toString" -> {
                c.call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            default -> {
                c.drop();
                return false;
            }
        }
    }

    private boolean emitEntryMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        c.refCast(ts.entryType);
        switch (name) {
            case "getKey" -> {
                c.structGet(ts.entryType, 0);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "getValue" -> {
                c.structGet(ts.entryType, 1);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "setValue" -> {
                fe.emitBoxed(args.get(0));
                c.i32Const(line).call(cols.fnEntrySetValue);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "equals" -> {
                fe.emitBoxed(args.get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "hashCode" -> {
                c.call(rt.fnHashCode);
                ctx.stackKind = PrimitiveKind.INT;
                return true;
            }
            case "toString" -> {
                c.call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            default -> {
                c.drop();
                return false;
            }
        }
    }

    private boolean emitIteratorMethod(String name, HirExpr recv, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        int it = ctx.newLocal("$itR", ts.anyRef());
        c.localSet(it);
        if ("hasNext".equals(name)) {
            c.localGet(it)
                    .refTest(ts.collectionIterType)
                    .if_(WasmValType.I32)
                    .localGet(it)
                    .refCast(ts.collectionIterType)
                    .call(cols.fnIterHasNext)
                    .else_()
                    .localGet(it)
                    .refTest(ts.rangeIterType)
                    .if_(WasmValType.I32)
                    .localGet(it)
                    .refCast(ts.rangeIterType)
                    .call(ranges.fnRangeIterHasNext)
                    .else_();
            emitCallZeroArgMember(it, "hasNext", line);
            c.call(rt.fnTruth).end().end();
            ctx.stackKind = PrimitiveKind.BOOL;
            return true;
        }
        if ("next".equals(name)) {
            c.localGet(it)
                    .refTest(ts.collectionIterType)
                    .if_(ts.anyRef())
                    .localGet(it)
                    .refCast(ts.collectionIterType)
                    .i32Const(line)
                    .call(cols.fnIterNext)
                    .else_()
                    .localGet(it)
                    .refTest(ts.rangeIterType)
                    .if_(ts.anyRef())
                    .localGet(it)
                    .refCast(ts.rangeIterType)
                    .i32Const(line)
                    .call(ranges.fnRangeIterNext)
                    .else_();
            emitCallZeroArgMember(it, "next", line);
            c.end().end();
            ctx.stackKind = PrimitiveKind.NONE;
            return true;
        }
        return false;
    }

    private boolean emitAppendableMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        if (!"append".equals(name) || args.size() != 1) {
            return false;
        }
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(recv);
        int r = ctx.newLocal("$appRecv", ts.anyRef());
        c.localSet(r);
        fe.emitBoxed(args.get(0));
        c.arrayNewFixed(ts.anyArrType, 1).localGet(r).i32Const(0).i32Const(line).call(rt.fnPrint);
        c.localGet(r);
        ctx.stackKind = PrimitiveKind.NONE;
        return true;
    }

    private boolean emitObjectMethod(String name, HirExpr recv, List<HirExpr> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        if ("toString".equals(name) && args.isEmpty()) {
            fe.emitBoxed(recv);
            c.call(rt.fnFormat);
            ctx.stackKind = PrimitiveKind.NONE;
            return true;
        }
        if ("equals".equals(name) && args.size() == 1) {
            fe.emitBoxed(recv);
            fe.emitBoxed(args.get(0));
            c.call(rt.fnEquals);
            ctx.stackKind = PrimitiveKind.BOOL;
            return true;
        }
        if ("hashCode".equals(name) && args.isEmpty()) {
            fe.emitBoxed(recv);
            c.call(rt.fnHashCode);
            ctx.stackKind = PrimitiveKind.INT;
            return true;
        }
        return false;
    }

    private static PrimitiveKind javaClassToPrimitiveKind(Class<?> cls, PrimitiveKind fallback) {
        if (cls == int.class) return PrimitiveKind.INT;
        if (cls == long.class) return PrimitiveKind.LONG;
        if (cls == float.class) return PrimitiveKind.FLOAT;
        if (cls == double.class) return PrimitiveKind.DOUBLE;
        return fallback;
    }

    private boolean emitMathMethod(
            String name,
            java.lang.reflect.Method method,
            List<HirExpr> args,
            PrimitiveKind retKind,
            int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        Class<?>[] pTypes = method != null ? method.getParameterTypes() : new Class<?>[0];
        if (args.size() == 1) {
            PrimitiveKind ak =
                    pTypes.length == 1
                            ? javaClassToPrimitiveKind(
                                    pTypes[0],
                                    retKind.isPrimitive() ? retKind : PrimitiveKind.DOUBLE)
                            : (retKind.isPrimitive() ? retKind : PrimitiveKind.DOUBLE);
            switch (name) {
                case "abs" -> {
                    fe.emitAs(args.get(0), ak);
                    switch (ak) {
                        case DOUBLE -> c.op(WasmInstr.SimpleOp.F64_ABS);
                        case FLOAT -> c.op(WasmInstr.SimpleOp.F32_ABS);
                        case INT -> {
                            int t = ctx.newLocal("$absI", WasmValType.I32);
                            c.localTee(t)
                                    .i32Const(0)
                                    .op(WasmInstr.SimpleOp.I32_LT_S)
                                    .if_(WasmValType.I32)
                                    .i32Const(0)
                                    .localGet(t)
                                    .op(WasmInstr.SimpleOp.I32_SUB)
                                    .else_()
                                    .localGet(t)
                                    .end();
                        }
                        case LONG -> {
                            int t = ctx.newLocal("$absL", WasmValType.I64);
                            c.localTee(t)
                                    .i64Const(0L)
                                    .op(WasmInstr.SimpleOp.I64_LT_S)
                                    .if_(WasmValType.I64)
                                    .i64Const(0L)
                                    .localGet(t)
                                    .op(WasmInstr.SimpleOp.I64_SUB)
                                    .else_()
                                    .localGet(t)
                                    .end();
                        }
                        default -> {
                            return false;
                        }
                    }
                    ctx.stackKind = ak;
                    return true;
                }
                case "sqrt" -> {
                    fe.emitAs(args.get(0), PrimitiveKind.DOUBLE);
                    c.op(WasmInstr.SimpleOp.F64_SQRT);
                    ctx.stackKind = PrimitiveKind.DOUBLE;
                    return true;
                }
                case "floor" -> {
                    fe.emitAs(args.get(0), PrimitiveKind.DOUBLE);
                    c.op(WasmInstr.SimpleOp.F64_FLOOR);
                    ctx.stackKind = PrimitiveKind.DOUBLE;
                    return true;
                }
                case "ceil" -> {
                    fe.emitAs(args.get(0), PrimitiveKind.DOUBLE);
                    c.op(WasmInstr.SimpleOp.F64_CEIL);
                    ctx.stackKind = PrimitiveKind.DOUBLE;
                    return true;
                }
                case "rint" -> {
                    fe.emitAs(args.get(0), PrimitiveKind.DOUBLE);
                    c.op(WasmInstr.SimpleOp.F64_NEAREST);
                    ctx.stackKind = PrimitiveKind.DOUBLE;
                    return true;
                }
                case "round" -> {
                    if (ak == PrimitiveKind.FLOAT) {
                        // Exact Java Math.round(float) -> int
                        int x = ctx.newLocal("$rndF", WasmValType.F32);
                        fe.emitAs(args.get(0), PrimitiveKind.FLOAT);
                        c.localTee(x)
                                .f32Const(0.49999997f)
                                .op(WasmInstr.SimpleOp.F32_EQ)
                                .if_(WasmValType.I32)
                                .i32Const(0)
                                .else_()
                                .localGet(x)
                                .op(WasmInstr.SimpleOp.F32_ABS)
                                .f32Const(8388608.0f)
                                .op(WasmInstr.SimpleOp.F32_GE)
                                .localGet(x)
                                .localGet(x)
                                .op(WasmInstr.SimpleOp.F32_NE)
                                .op(WasmInstr.SimpleOp.I32_OR)
                                .if_(WasmValType.I32)
                                .localGet(x)
                                .op(WasmInstr.SimpleOp.I32_TRUNC_SAT_F32_S)
                                .else_()
                                .localGet(x)
                                .f32Const(0.5f)
                                .op(WasmInstr.SimpleOp.F32_ADD)
                                .op(WasmInstr.SimpleOp.F32_FLOOR)
                                .op(WasmInstr.SimpleOp.I32_TRUNC_SAT_F32_S)
                                .end()
                                .end();
                        ctx.stackKind = PrimitiveKind.INT;
                    } else {
                        // Exact Java Math.round(double) -> long
                        int x = ctx.newLocal("$rndD", WasmValType.F64);
                        fe.emitAs(args.get(0), PrimitiveKind.DOUBLE);
                        c.localTee(x)
                                .f64Const(0.49999999999999994)
                                .op(WasmInstr.SimpleOp.F64_EQ)
                                .if_(WasmValType.I64)
                                .i64Const(0L)
                                .else_()
                                .localGet(x)
                                .op(WasmInstr.SimpleOp.F64_ABS)
                                .f64Const(4503599627370496.0)
                                .op(WasmInstr.SimpleOp.F64_GE)
                                .localGet(x)
                                .localGet(x)
                                .op(WasmInstr.SimpleOp.F64_NE)
                                .op(WasmInstr.SimpleOp.I32_OR)
                                .if_(WasmValType.I64)
                                .localGet(x)
                                .op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S)
                                .else_()
                                .localGet(x)
                                .f64Const(0.5)
                                .op(WasmInstr.SimpleOp.F64_ADD)
                                .op(WasmInstr.SimpleOp.F64_FLOOR)
                                .op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S)
                                .end()
                                .end();
                        ctx.stackKind = PrimitiveKind.LONG;
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        if (args.size() == 2) {
            PrimitiveKind ak =
                    pTypes.length == 2
                            ? javaClassToPrimitiveKind(
                                    pTypes[0],
                                    retKind.isPrimitive() ? retKind : PrimitiveKind.DOUBLE)
                            : (retKind.isPrimitive() ? retKind : PrimitiveKind.DOUBLE);
            switch (name) {
                case "min" -> {
                    fe.emitAs(args.get(0), ak);
                    fe.emitAs(args.get(1), ak);
                    switch (ak) {
                        case DOUBLE -> c.op(WasmInstr.SimpleOp.F64_MIN);
                        case FLOAT -> c.op(WasmInstr.SimpleOp.F32_MIN);
                        case INT -> {
                            int a = ctx.newLocal("$minA", WasmValType.I32);
                            int b = ctx.newLocal("$minB", WasmValType.I32);
                            c.localSet(b)
                                    .localSet(a)
                                    .localGet(a)
                                    .localGet(b)
                                    .localGet(a)
                                    .localGet(b)
                                    .op(WasmInstr.SimpleOp.I32_LT_S)
                                    .select();
                        }
                        case LONG -> {
                            int a = ctx.newLocal("$minA", WasmValType.I64);
                            int b = ctx.newLocal("$minB", WasmValType.I64);
                            c.localSet(b)
                                    .localSet(a)
                                    .localGet(a)
                                    .localGet(b)
                                    .localGet(a)
                                    .localGet(b)
                                    .op(WasmInstr.SimpleOp.I64_LT_S)
                                    .select();
                        }
                        default -> {
                            return false;
                        }
                    }
                    ctx.stackKind = ak;
                    return true;
                }
                case "max" -> {
                    fe.emitAs(args.get(0), ak);
                    fe.emitAs(args.get(1), ak);
                    switch (ak) {
                        case DOUBLE -> c.op(WasmInstr.SimpleOp.F64_MAX);
                        case FLOAT -> c.op(WasmInstr.SimpleOp.F32_MAX);
                        case INT -> {
                            int a = ctx.newLocal("$maxA", WasmValType.I32);
                            int b = ctx.newLocal("$maxB", WasmValType.I32);
                            c.localSet(b)
                                    .localSet(a)
                                    .localGet(a)
                                    .localGet(b)
                                    .localGet(a)
                                    .localGet(b)
                                    .op(WasmInstr.SimpleOp.I32_GT_S)
                                    .select();
                        }
                        case LONG -> {
                            int a = ctx.newLocal("$maxA", WasmValType.I64);
                            int b = ctx.newLocal("$maxB", WasmValType.I64);
                            c.localSet(b)
                                    .localSet(a)
                                    .localGet(a)
                                    .localGet(b)
                                    .localGet(a)
                                    .localGet(b)
                                    .op(WasmInstr.SimpleOp.I64_GT_S)
                                    .select();
                        }
                        default -> {
                            return false;
                        }
                    }
                    ctx.stackKind = ak;
                    return true;
                }
                case "copySign" -> {
                    fe.emitAs(args.get(0), ak);
                    fe.emitAs(args.get(1), ak);
                    c.op(
                            ak == PrimitiveKind.FLOAT
                                    ? WasmInstr.SimpleOp.F32_COPYSIGN
                                    : WasmInstr.SimpleOp.F64_COPYSIGN);
                    ctx.stackKind = ak;
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }
}
