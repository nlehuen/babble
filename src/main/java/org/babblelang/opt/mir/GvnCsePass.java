package org.babblelang.opt.mir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirEffect;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirModule;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;
import org.babblelang.opt.PassContext;

/**
 * Dominator-scoped Global Value Numbering and Common Subexpression Elimination ({@code O5} + {@code
 * O3}) over SSA {@link MirModule} ({@code BIP014-I3} §6.4).
 *
 * <p>Eliminates redundant pure computations, commutative binary expressions/calls, idempotent call
 * chains ({@code f(f(x)) -> f(x)}), inverse and chained representation conversions ({@code
 * unbox(box(a)) -> a}, sound {@code box(unbox(a)) -> a} and {@code box(nonnull(a)) -> a}, lossless
 * primitive widening chains, redundant exact-primitive {@code CheckCast}/{@code Widen}), {@code
 * ArrayLen}, and alias-epoch-protected {@code LoadField}/{@code LoadJavaField} reads while keeping
 * {@link MirInst.Narrow} anchors intact.
 */
public final class GvnCsePass implements MirPass {
    @Override
    public String name() {
        return "GvnCsePass";
    }

    @Override
    public Result run(MirModule module, PassContext context) {
        int total = 0;
        Map<Integer, MirFunction> updated = new LinkedHashMap<>(module.functions().size() * 2);
        for (Map.Entry<Integer, MirFunction> e : module.functions().entrySet()) {
            FnResult r = runOnFunction(e.getValue());
            updated.put(e.getKey(), r.fn);
            total += r.rewrites;
        }
        return total == 0
                ? Result.unchanged(module)
                : new Result(module.withFunctions(updated), total);
    }

    private record FnResult(MirFunction fn, int rewrites) {}

    private static final class EpochState {
        int sharedEpoch = 0;
        final Map<Integer, Integer> uniqueEpochs = new HashMap<>();

        EpochState copy() {
            EpochState c = new EpochState();
            c.sharedEpoch = this.sharedEpoch;
            c.uniqueEpochs.putAll(this.uniqueEpochs);
            return c;
        }

        int epochFor(MirEffect.AliasClass alias) {
            if (alias instanceof MirEffect.AliasClass.Frozen
                    || alias instanceof MirEffect.AliasClass.None) {
                return 0;
            }
            if (alias instanceof MirEffect.AliasClass.UniqueParam u) {
                return uniqueEpochs.getOrDefault(u.paramIndex(), 0);
            }
            return sharedEpoch;
        }

        void onEffect(MirEffect fx) {
            if (fx.kind() == MirEffect.Kind.IMPURE) {
                bumpAllMutable();
            } else if (fx.kind() == MirEffect.Kind.WRITE) {
                if (fx.alias() instanceof MirEffect.AliasClass.UniqueParam u) {
                    uniqueEpochs.merge(u.paramIndex(), 1, Integer::sum);
                } else if (fx.alias() instanceof MirEffect.AliasClass.Shared) {
                    sharedEpoch++;
                }
            }
        }

        void bumpAllMutable() {
            sharedEpoch++;
            for (Integer k : new ArrayList<>(uniqueEpochs.keySet())) {
                uniqueEpochs.merge(k, 1, Integer::sum);
            }
        }
    }

    private static FnResult runOnFunction(MirFunction fn) {
        MirCfg cfg = MirCfg.analyze(fn);
        Map<Integer, MirBlock> blockMap = fn.blockMap();
        Map<Integer, MirVal> subst = new HashMap<>();
        Map<Integer, List<MirInst>> rewrittenInsts = new HashMap<>();
        Map<Integer, MirInst> defInst = new HashMap<>();
        boolean[] guaranteedLive = computeGuaranteedLiveUses(fn);
        int[] rewrites = {0};

        visitDom(
                fn.entryBlockId(),
                cfg,
                blockMap,
                new HashMap<>(),
                new EpochState(),
                subst,
                rewrittenInsts,
                defInst,
                guaranteedLive,
                rewrites);

        if (rewrites[0] == 0) {
            return new FnResult(fn, 0);
        }
        List<MirBlock> out = new ArrayList<>(fn.blocks().size());
        for (MirBlock b : fn.blocks()) {
            List<MirInst> insts = rewrittenInsts.getOrDefault(b.id(), b.insts());
            MirTerminator term = b.terminator().remapUses(v -> resolve(subst, v));
            out.add(new MirBlock(b.id(), b.params(), insts, term, b.handlers(), b.handlerEntry()));
        }
        return new FnResult(fn.withBlocks(out, fn.nextValId(), fn.nextBlockId()), rewrites[0]);
    }

    /**
     * Marks SSA values that are directly consumed by an unremovable (side-effecting/throwing)
     * instruction or a function-exiting terminator ({@code Ret}/{@code Throw}/{@code TailCall}).
     * Such values remain executed regardless of any {@code box(unbox(a)) -> a} rewrite.
     */
    private static boolean[] computeGuaranteedLiveUses(MirFunction fn) {
        int n = Math.max(0, fn.nextValId());
        boolean[] live = new boolean[n];
        for (MirBlock b : fn.blocks()) {
            for (MirInst inst : b.insts()) {
                if (!inst.effect().isRemovableIfUnused()) {
                    for (MirVal u : inst.usedValues()) {
                        if (u.id() >= 0 && u.id() < n) {
                            live[u.id()] = true;
                        }
                    }
                }
            }
            MirTerminator t = b.terminator();
            if (t instanceof MirTerminator.Ret
                    || t instanceof MirTerminator.Throw
                    || t instanceof MirTerminator.TailCall) {
                for (MirVal u : t.usedValues()) {
                    if (u.id() >= 0 && u.id() < n) {
                        live[u.id()] = true;
                    }
                }
            }
        }
        return live;
    }

    private static void visitDom(
            int blockId,
            MirCfg cfg,
            Map<Integer, MirBlock> blockMap,
            Map<Object, MirVal> parentTable,
            EpochState parentEpochs,
            Map<Integer, MirVal> subst,
            Map<Integer, List<MirInst>> outInsts,
            Map<Integer, MirInst> defInst,
            boolean[] guaranteedLive,
            int[] rewrites) {
        MirBlock b = blockMap.get(blockId);
        if (b == null) return;
        Map<Object, MirVal> table = new HashMap<>(parentTable);
        EpochState epochs = parentEpochs.copy();

        if (b.handlerEntry() || cfg.predecessors(blockId).size() > 1) {
            epochs.bumpAllMutable();
        }

        List<MirInst> kept = new ArrayList<>(b.insts().size());
        for (MirInst raw : b.insts()) {
            MirInst inst = raw.remapUses(v -> resolve(subst, v));
            MirVal res = inst.result();

            // Check idempotent nested call f(f(x)) -> f(x).
            MirVal idemTarget = tryCollapseIdempotent(inst, defInst);
            if (idemTarget != null && res != null && idemTarget.kind() == res.kind()) {
                subst.put(res.id(), idemTarget);
                rewrites[0]++;
                continue;
            }

            // Simplify representation conversions: unbox(box(a)) -> a, sound box(unbox(a)) -> a,
            // box(nonnull(a)) -> a, redundant exact-primitive CheckCast/Widen, and widening chains.
            MirVal simplifiedVal = trySimplifyToVal(inst, defInst, guaranteedLive);
            if (simplifiedVal != null && res != null && simplifiedVal.kind() == res.kind()) {
                subst.put(res.id(), simplifiedVal);
                rewrites[0]++;
                continue;
            }
            MirInst rewritten = trySimplifyConvertChain(inst, defInst);
            if (rewritten != inst) {
                inst = rewritten;
                rewrites[0]++;
            }

            Object key = valueKey(inst, epochs);
            if (key != null && res != null) {
                MirVal existing = table.get(key);
                if (existing != null && existing.kind() == res.kind()) {
                    subst.put(res.id(), existing);
                    rewrites[0]++;
                    continue;
                }
                table.put(key, res);
            }
            epochs.onEffect(inst.effect());
            if (res != null) {
                defInst.put(res.id(), inst);
            }
            kept.add(inst);
        }
        outInsts.put(blockId, kept);

        boolean hasHandlers = !b.handlers().isEmpty();
        for (int childId : cfg.domChildren(blockId)) {
            MirBlock child = blockMap.get(childId);
            boolean normalSinglePred =
                    !hasHandlers
                            || (child != null
                                    && !child.handlerEntry()
                                    && cfg.predecessors(childId).size() == 1);
            visitDom(
                    childId,
                    cfg,
                    blockMap,
                    normalSinglePred ? table : parentTable,
                    normalSinglePred ? epochs : parentEpochs,
                    subst,
                    outInsts,
                    defInst,
                    guaranteedLive,
                    rewrites);
        }
    }

    private static MirVal trySimplifyToVal(
            MirInst inst, Map<Integer, MirInst> defInst, boolean[] guaranteedLive) {
        if (inst instanceof MirInst.Convert c) {
            MirVal op = c.operand();
            PrimitiveKind target = c.targetKind();
            if (op.kind() == target) {
                return op;
            }
            if (target.isPrimitive() && op.kind() == PrimitiveKind.NONE) {
                // unbox_T(box_T(a)) -> a (safe through zero-cost aliases and Narrow anchors,
                // because box_T(a) is guaranteed non-null exact Box(T) and a is already a
                // primitive).
                MirVal unwrapped = unwrapAlias(op, defInst, true);
                MirInst inner = defInst.get(unwrapped.id());
                if (inner instanceof MirInst.Convert ic
                        && ic.targetKind() == PrimitiveKind.NONE
                        && ic.operand().kind() == target) {
                    return ic.operand();
                }
                // unbox_T(widen#R(box_T(a))) -> a when rank R targets T and a is numeric T.
                if (inner instanceof MirInst.Widen w
                        && targetKindForRank(w.wideningRank()) == target) {
                    MirVal wOp = unwrapAlias(w.operand(), defInst, true);
                    MirInst wInner = defInst.get(wOp.id());
                    if (wInner instanceof MirInst.Convert ic
                            && ic.targetKind() == PrimitiveKind.NONE
                            && ic.operand().kind() == target
                            && isNumericKind(target)) {
                        return ic.operand();
                    }
                }
            } else if (target == PrimitiveKind.NONE && op.kind().isPrimitive()) {
                PrimitiveKind prim = op.kind();
                // Do not skip Narrow here so downstream uses keep their Narrow guard anchor.
                MirVal innerVal = unwrapAlias(op, defInst, false);
                MirInst inner = defInst.get(innerVal.id());
                if (inner instanceof MirInst.Convert ic
                        && ic.operand().kind() == PrimitiveKind.NONE
                        && ic.targetKind() == prim) {
                    MirVal a = ic.operand();
                    boolean safeToBypassUnbox =
                            isGuaranteedNonNullBox(a, prim, defInst, true)
                                    || (innerVal.id() >= 0
                                            && innerVal.id() < guaranteedLive.length
                                            && guaranteedLive[innerVal.id()]);
                    if (safeToBypassUnbox) {
                        return a;
                    }
                }
                // box_T(nonnull_T(a)) -> a (NonNull is PURE_MAY_THROW, so it remains executed
                // and validates a != null && a instanceof Box(T)).
                if (inner instanceof MirInst.NonNull nn
                        && nn.result().kind() == prim
                        && nn.operand().kind() == PrimitiveKind.NONE) {
                    return nn.operand();
                }
            }
            return null;
        }
        if (inst instanceof MirInst.CheckCast cc
                && cc.samTarget() == null
                && cc.descriptor() != null
                && cc.result().kind() == PrimitiveKind.NONE) {
            PrimitiveKind exact = primitiveForExactDescriptor(cc.descriptor());
            if (exact != null && isGuaranteedNonNullBox(cc.operand(), exact, defInst, false)) {
                return cc.operand();
            }
            return null;
        }
        if (inst instanceof MirInst.Widen w
                && w.result().kind() == PrimitiveKind.NONE
                && w.operand().kind() == PrimitiveKind.NONE) {
            PrimitiveKind target = targetKindForRank(w.wideningRank());
            if (target != PrimitiveKind.NONE
                    && isGuaranteedNonNullBox(w.operand(), target, defInst, false)) {
                return w.operand();
            }
        }
        return null;
    }

    private static MirInst trySimplifyConvertChain(MirInst inst, Map<Integer, MirInst> defInst) {
        if (!(inst instanceof MirInst.Convert c) || !c.targetKind().isPrimitive()) {
            return inst;
        }
        PrimitiveKind target = c.targetKind();
        if (c.operand().kind().isPrimitive()) {
            MirVal innerVal = unwrapAlias(c.operand(), defInst, false);
            MirInst inner = defInst.get(innerVal.id());
            if (inner instanceof MirInst.Convert ic
                    && isLosslessPrimitiveWiden(ic.operand().kind(), ic.targetKind())
                    && isPrimitiveWiden(ic.targetKind(), target)) {
                return new MirInst.Convert(c.result(), ic.operand(), target, c.line());
            }
        } else if (c.operand().kind() == PrimitiveKind.NONE) {
            // unbox_T(widen#R(box_S(a))) -> Convert(T, a) when S -> T is a numeric widening.
            MirVal innerVal = unwrapAlias(c.operand(), defInst, true);
            MirInst inner = defInst.get(innerVal.id());
            if (inner instanceof MirInst.Widen w && targetKindForRank(w.wideningRank()) == target) {
                MirVal wOp = unwrapAlias(w.operand(), defInst, true);
                MirInst wInner = defInst.get(wOp.id());
                if (wInner instanceof MirInst.Convert ic
                        && ic.targetKind() == PrimitiveKind.NONE
                        && isNumericKind(ic.operand().kind())
                        && isPrimitiveWiden(ic.operand().kind(), target)) {
                    return new MirInst.Convert(c.result(), ic.operand(), target, c.line());
                }
            }
        }
        return inst;
    }

    private static MirVal unwrapAlias(
            MirVal v, Map<Integer, MirInst> defInst, boolean throughNarrow) {
        MirVal cur = v;
        for (int hops = 0; hops < 16; hops++) {
            MirInst d = defInst.get(cur.id());
            if (d instanceof MirInst.Copy cp) {
                cur = cp.source();
            } else if (d instanceof MirInst.Move mv) {
                cur = mv.operand();
            } else if (d instanceof MirInst.Freeze fr) {
                cur = fr.operand();
            } else if (throughNarrow && d instanceof MirInst.Narrow nr) {
                cur = nr.operand();
            } else {
                break;
            }
        }
        return cur;
    }

    /**
     * Returns true when {@code val} (of kind {@link PrimitiveKind#NONE}) is statically proven to
     * hold a non-null instance of the exact wrapper class for {@code expected}.
     *
     * <p>{@code allowNonNullNarrow} is only true when called from {@code box_T(unbox_T(a))}, where
     * {@code TypeChecker} already guaranteed the unboxed variable's static type was strictly {@code
     * T?} before the {@code != null} guard (never {@code any?}).
     */
    private static boolean isGuaranteedNonNullBox(
            MirVal val,
            PrimitiveKind expected,
            Map<Integer, MirInst> defInst,
            boolean allowNonNullNarrow) {
        if (expected == null || !expected.isPrimitive() || val.kind() != PrimitiveKind.NONE) {
            return false;
        }
        MirVal cur = unwrapAlias(val, defInst, false);
        MirInst def = defInst.get(cur.id());
        if (def instanceof MirInst.Convert ic) {
            return ic.targetKind() == PrimitiveKind.NONE && ic.operand().kind() == expected;
        }
        if (def instanceof MirInst.Const cn) {
            return isBoxedInstanceOf(cn.value(), expected);
        }
        if (def instanceof MirInst.CheckCast cc) {
            return cc.samTarget() == null
                    && exactPrimitiveDescriptor(expected).equals(cc.descriptor());
        }
        if (def instanceof MirInst.Narrow nr) {
            if (nr.narrowKind() == MirInst.NarrowKind.IS_TYPE) {
                return matchesExactPrimitiveNarrow(expected, nr.targetDesc());
            }
            return allowNonNullNarrow && nr.narrowKind() == MirInst.NarrowKind.NON_NULL;
        }
        return false;
    }

    private static String exactPrimitiveDescriptor(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> "byte|byte|";
            case SHORT -> "short|short|";
            case INT -> "int|int|";
            case LONG -> "long|long|";
            case FLOAT -> "float|float|";
            case DOUBLE -> "double|double|";
            case BOOL -> "bool|bool|";
            case CHAR -> "char|char|";
            case NONE -> "";
        };
    }

    private static PrimitiveKind primitiveForExactDescriptor(String desc) {
        return switch (desc) {
            case "byte|byte|" -> PrimitiveKind.BYTE;
            case "short|short|" -> PrimitiveKind.SHORT;
            case "int|int|" -> PrimitiveKind.INT;
            case "long|long|" -> PrimitiveKind.LONG;
            case "float|float|" -> PrimitiveKind.FLOAT;
            case "double|double|" -> PrimitiveKind.DOUBLE;
            case "bool|bool|" -> PrimitiveKind.BOOL;
            case "char|char|" -> PrimitiveKind.CHAR;
            default -> null;
        };
    }

    private static boolean matchesExactPrimitiveNarrow(PrimitiveKind expected, String desc) {
        if (desc == null) return false;
        if (exactPrimitiveDescriptor(expected).equals(desc)) return true;
        return switch (expected) {
            case BYTE -> "byte".equals(desc);
            case SHORT -> "short".equals(desc);
            case INT -> "int".equals(desc);
            case LONG -> "long".equals(desc);
            case FLOAT -> "float".equals(desc);
            case DOUBLE -> "double".equals(desc);
            case BOOL -> "bool".equals(desc);
            case CHAR -> "char".equals(desc);
            case NONE -> false;
        };
    }

    private static boolean isBoxedInstanceOf(Object v, PrimitiveKind expected) {
        if (v == null) return false;
        return switch (expected) {
            case BYTE -> v instanceof Byte;
            case SHORT -> v instanceof Short;
            case INT -> v instanceof Integer;
            case LONG -> v instanceof Long;
            case FLOAT -> v instanceof Float;
            case DOUBLE -> v instanceof Double;
            case BOOL -> v instanceof Boolean;
            case CHAR -> v instanceof Character;
            case NONE -> false;
        };
    }

    private static PrimitiveKind targetKindForRank(int rank) {
        return switch (rank) {
            case 1 -> PrimitiveKind.SHORT;
            case 2 -> PrimitiveKind.INT;
            case 3 -> PrimitiveKind.LONG;
            case 4 -> PrimitiveKind.FLOAT;
            case 5 -> PrimitiveKind.DOUBLE;
            default -> PrimitiveKind.NONE;
        };
    }

    private static boolean isNumericKind(PrimitiveKind kind) {
        return kind.isNumeric();
    }

    private static int numericOrder(PrimitiveKind kind) {
        return kind.numericRank();
    }

    private static boolean isPrimitiveWiden(PrimitiveKind from, PrimitiveKind to) {
        if (!from.isPrimitive() || !to.isPrimitive() || from == to) {
            return false;
        }
        if (from == PrimitiveKind.CHAR) {
            return numericOrder(to) >= 2;
        }
        int f = numericOrder(from);
        int t = numericOrder(to);
        return f >= 0 && t >= 0 && f < t;
    }

    /** True when {@code from -> to} is a bit-exact (zero-rounding) primitive widening. */
    private static boolean isLosslessPrimitiveWiden(PrimitiveKind from, PrimitiveKind to) {
        if (!from.isPrimitive() || !to.isPrimitive() || from == to) {
            return false;
        }
        return switch (from) {
            case BYTE ->
                    to == PrimitiveKind.SHORT
                            || to == PrimitiveKind.INT
                            || to == PrimitiveKind.LONG
                            || to == PrimitiveKind.FLOAT
                            || to == PrimitiveKind.DOUBLE;
            case SHORT, CHAR ->
                    to == PrimitiveKind.INT
                            || to == PrimitiveKind.LONG
                            || to == PrimitiveKind.FLOAT
                            || to == PrimitiveKind.DOUBLE;
            case INT -> to == PrimitiveKind.LONG || to == PrimitiveKind.DOUBLE;
            default -> false;
        };
    }

    private static MirVal tryCollapseIdempotent(MirInst inst, Map<Integer, MirInst> defInst) {
        if (inst instanceof MirInst.CallJava cj
                && cj.idempotent()
                && cj.effect().isPureFloatable()
                && cj.args().size() == 1
                && cj.receiver() == null) {
            MirInst inner = defInst.get(cj.args().get(0).id());
            if (inner instanceof MirInst.CallJava innerCj
                    && innerCj.receiver() == null
                    && innerCj.target().method().equals(cj.target().method())) {
                return innerCj.result();
            }
        }
        if (inst instanceof MirInst.CallBabble cb
                && cb.idempotent()
                && cb.effect().isPureFloatable()
                && cb.args().size() == 1) {
            MirInst inner = defInst.get(cb.args().get(0).id());
            if (inner instanceof MirInst.CallBabble innerCb && sameCallBabbleTarget(innerCb, cb)) {
                return innerCb.result();
            }
        }
        return null;
    }

    private static boolean sameCallBabbleTarget(MirInst.CallBabble a, MirInst.CallBabble b) {
        if (a.externalTarget() != null || b.externalTarget() != null) {
            return a.externalTarget() != null
                    && b.externalTarget() != null
                    && a.externalTarget().canonicalKey().equals(b.externalTarget().canonicalKey());
        }
        return a.targetFunctionId() == b.targetFunctionId();
    }

    private static Object valueKey(MirInst inst, EpochState epochs) {
        return switch (inst) {
            case MirInst.Const c -> new ConstKey(c.result().kind(), normalizeConstKey(c.value()));
            case MirInst.Unary u ->
                    u.effect().isPureFloatable()
                            ? new UnaryKey(u.op(), u.result().kind(), u.operand().id())
                            : null;
            case MirInst.Binary b -> {
                if (!b.effect().isPureFloatable()) yield null;
                int l = b.left().id();
                int r = b.right().id();
                if (isCommutative(b.op(), b.stringConcat()) && l > r) {
                    int t = l;
                    l = r;
                    r = t;
                }
                yield new BinaryKey(b.op(), b.stringConcat(), b.result().kind(), l, r);
            }
            case MirInst.Convert c -> new ConvertKey(c.targetKind(), c.operand().id());
            case MirInst.Widen w ->
                    new WidenKey(w.wideningRank(), w.result().kind(), w.operand().id());
            case MirInst.LoadField lf ->
                    lf.effect().isReadFloatable()
                            ? new FieldKey(
                                    lf.receiver().id(),
                                    lf.classId(),
                                    lf.memberIndex(),
                                    lf.memberName(),
                                    lf.result().kind(),
                                    epochs.epochFor(lf.effect().alias()))
                            : null;
            case MirInst.LoadJavaField lj ->
                    lj.effect().isReadFloatable()
                            ? new JavaFieldKey(
                                    lj.receiver() != null ? lj.receiver().id() : -1,
                                    lj.target().field(),
                                    lj.result().kind(),
                                    epochs.epochFor(lj.effect().alias()))
                            : null;
            case MirInst.LoadExport le ->
                    le.effect().isPureFloatable()
                            ? new LoadExportKey(
                                    le.module().id(), le.target().index(), le.target().name())
                            : null;
            case MirInst.ArrayLen al -> new ArrayLenKey(al.array().id());
            case MirInst.CallBabble cb ->
                    cb.effect().isPureFloatable()
                            ? new CallBabbleKey(
                                    cb.targetFunctionId(),
                                    cb.externalTarget() != null
                                            ? cb.externalTarget().canonicalKey()
                                            : null,
                                    cb.result().kind(),
                                    canonicalArgs(cb.args(), cb.commutative()))
                            : null;
            case MirInst.CallJava cj ->
                    cj.effect().isPureFloatable()
                            ? new CallJavaKey(
                                    cj.receiver() != null ? cj.receiver().id() : -1,
                                    cj.target().method(),
                                    cj.result().kind(),
                                    canonicalArgs(cj.args(), cj.commutative()))
                            : null;
            case MirInst.CallMethod cm ->
                    cm.effect().isPureFloatable() && cm.targetFunctionId() >= 0
                            ? new CallMethodKey(
                                    cm.receiver().id(),
                                    cm.targetFunctionId(),
                                    cm.result().kind(),
                                    canonicalArgs(cm.args(), cm.commutative()))
                            : null;
            case MirInst.SlotRead sr ->
                    sr.effect().isPureFloatable()
                            ? new SlotReadKey(sr.var(), sr.result().kind())
                            : null;
            case MirInst.NameRead nr -> {
                if (nr.effect().isPureFloatable()) {
                    yield new PureNameReadKey(nr.name(), nr.result().kind());
                }
                if (nr.finalBinding() && nr.effect().kind() == MirEffect.Kind.PURE) {
                    // PURE_MAY_THROW: once a dominating read succeeds on a non-exception path, the
                    // final slot is initialized forever (catch handlers receive parentTable via
                    // visitDom).
                    yield new PureNameReadKey(nr.name(), nr.result().kind());
                }
                if (nr.effect().isReadFloatable()) {
                    yield new NameReadKey(
                            nr.name(), nr.result().kind(), epochs.epochFor(nr.effect().alias()));
                }
                yield null;
            }
            default -> null;
        };
    }

    private static List<Integer> canonicalArgs(List<MirVal> args, boolean commutative) {
        if (commutative && args.size() == 2) {
            int a = args.get(0).id();
            int b = args.get(1).id();
            return a <= b ? List.of(a, b) : List.of(b, a);
        }
        List<Integer> ids = new ArrayList<>(args.size());
        for (MirVal v : args) {
            ids.add(v.id());
        }
        return ids;
    }

    private static boolean isCommutative(HirExpr.BinaryOp op, boolean stringConcat) {
        if (stringConcat) return false;
        return op == HirExpr.BinaryOp.ADD
                || op == HirExpr.BinaryOp.MUL
                || op == HirExpr.BinaryOp.EQ
                || op == HirExpr.BinaryOp.NEQ;
    }

    private static Object normalizeConstKey(Object v) {
        if (v == null) return NullMarker.INSTANCE;
        if (v instanceof Double d) return new DoubleBits(Double.doubleToRawLongBits(d));
        if (v instanceof Float f) return new FloatBits(Float.floatToRawIntBits(f));
        return v;
    }

    private static MirVal resolve(Map<Integer, MirVal> subst, MirVal v) {
        MirVal cur = v;
        while (subst.containsKey(cur.id())) {
            cur = subst.get(cur.id());
        }
        return cur;
    }

    private enum NullMarker {
        INSTANCE
    }

    private record DoubleBits(long bits) {}

    private record FloatBits(int bits) {}

    private record ConstKey(PrimitiveKind kind, Object value) {}

    private record UnaryKey(HirExpr.UnaryOp op, PrimitiveKind kind, int operandId) {}

    private record BinaryKey(
            HirExpr.BinaryOp op,
            boolean stringConcat,
            PrimitiveKind kind,
            int leftId,
            int rightId) {}

    private record ConvertKey(PrimitiveKind targetKind, int operandId) {}

    private record WidenKey(int rank, PrimitiveKind kind, int operandId) {}

    private record FieldKey(
            int recvId, int classId, int memberIdx, String name, PrimitiveKind kind, int epoch) {}

    private record JavaFieldKey(int recvId, Field field, PrimitiveKind kind, int epoch) {}

    private record LoadExportKey(int moduleId, int exportIndex, String exportName) {}

    private record ArrayLenKey(int arrayId) {}

    private record CallBabbleKey(
            int fnId, String externalKey, PrimitiveKind kind, List<Integer> argIds) {}

    private record CallJavaKey(
            int recvId, Method method, PrimitiveKind kind, List<Integer> argIds) {}

    private record CallMethodKey(int recvId, int fnId, PrimitiveKind kind, List<Integer> argIds) {}

    private record SlotReadKey(org.babblelang.ir.hir.HirVar var, PrimitiveKind kind) {}

    private record PureNameReadKey(String name, PrimitiveKind kind) {}

    private record NameReadKey(String name, PrimitiveKind kind, int epoch) {}
}
