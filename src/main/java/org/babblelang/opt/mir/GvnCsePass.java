package org.babblelang.opt.mir;

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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dominator-scoped Global Value Numbering and Common Subexpression Elimination ({@code O5} +
 * {@code O3}) over SSA {@link MirModule} ({@code BIP014-P3} §6.4).
 *
 * <p>Eliminates redundant pure computations, commutative binary expressions/calls, idempotent
 * call chains ({@code f(f(x)) -> f(x)}), {@code ArrayLen}, and alias-epoch-protected
 * {@code LoadField}/{@code LoadJavaField} reads while keeping {@link MirInst.Narrow} anchors intact.
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
        return total == 0 ? Result.unchanged(module) : new Result(module.withFunctions(updated), total);
    }

    private record FnResult(MirFunction fn, int rewrites) {
    }

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
            if (alias instanceof MirEffect.AliasClass.Frozen || alias instanceof MirEffect.AliasClass.None) {
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
        int[] rewrites = {0};

        visitDom(fn.entryBlockId(), cfg, blockMap, new HashMap<>(), new EpochState(),
                subst, rewrittenInsts, defInst, rewrites);

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

    private static void visitDom(int blockId, MirCfg cfg, Map<Integer, MirBlock> blockMap,
                                 Map<Object, MirVal> parentTable, EpochState parentEpochs,
                                 Map<Integer, MirVal> subst, Map<Integer, List<MirInst>> outInsts,
                                 Map<Integer, MirInst> defInst, int[] rewrites) {
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
            boolean normalSinglePred = !hasHandlers
                    || (child != null && !child.handlerEntry() && cfg.predecessors(childId).size() == 1);
            visitDom(childId, cfg, blockMap,
                    normalSinglePred ? table : parentTable,
                    normalSinglePred ? epochs : parentEpochs,
                    subst, outInsts, defInst, rewrites);
        }
    }

    private static MirVal tryCollapseIdempotent(MirInst inst, Map<Integer, MirInst> defInst) {
        if (inst instanceof MirInst.CallJava cj && cj.idempotent() && cj.effect().isPureFloatable()
                && cj.args().size() == 1 && cj.receiver() == null) {
            MirInst inner = defInst.get(cj.args().get(0).id());
            if (inner instanceof MirInst.CallJava innerCj && innerCj.receiver() == null
                    && innerCj.target().method().equals(cj.target().method())) {
                return innerCj.result();
            }
        }
        if (inst instanceof MirInst.CallBabble cb && cb.idempotent() && cb.effect().isPureFloatable()
                && cb.args().size() == 1) {
            MirInst inner = defInst.get(cb.args().get(0).id());
            if (inner instanceof MirInst.CallBabble innerCb && innerCb.targetFunctionId() == cb.targetFunctionId()) {
                return innerCb.result();
            }
        }
        return null;
    }

    private static Object valueKey(MirInst inst, EpochState epochs) {
        return switch (inst) {
            case MirInst.Const c -> new ConstKey(c.result().kind(), normalizeConstKey(c.value()));
            case MirInst.Unary u -> u.effect().isPureFloatable()
                    ? new UnaryKey(u.op(), u.result().kind(), u.operand().id()) : null;
            case MirInst.Binary b -> {
                if (!b.effect().isPureFloatable()) yield null;
                int l = b.left().id();
                int r = b.right().id();
                if (isCommutative(b.op(), b.stringConcat()) && l > r) {
                    int t = l; l = r; r = t;
                }
                yield new BinaryKey(b.op(), b.stringConcat(), b.result().kind(), l, r);
            }
            case MirInst.Convert c -> new ConvertKey(c.targetKind(), c.operand().id());
            case MirInst.Widen w -> new WidenKey(w.wideningRank(), w.result().kind(), w.operand().id());
            case MirInst.LoadField lf -> lf.effect().isReadFloatable()
                    ? new FieldKey(lf.receiver().id(), lf.classId(), lf.memberIndex(), lf.memberName(),
                    lf.result().kind(), epochs.epochFor(lf.effect().alias())) : null;
            case MirInst.LoadJavaField lj -> lj.effect().isReadFloatable()
                    ? new JavaFieldKey(lj.receiver() != null ? lj.receiver().id() : -1, lj.target().field(),
                    lj.result().kind(), epochs.epochFor(lj.effect().alias())) : null;
            case MirInst.ArrayLen al -> new ArrayLenKey(al.array().id());
            case MirInst.CallBabble cb -> cb.effect().isPureFloatable()
                    ? new CallBabbleKey(cb.targetFunctionId(), cb.result().kind(), canonicalArgs(cb.args(), cb.commutative())) : null;
            case MirInst.CallJava cj -> cj.effect().isPureFloatable()
                    ? new CallJavaKey(cj.receiver() != null ? cj.receiver().id() : -1, cj.target().method(),
                    cj.result().kind(), canonicalArgs(cj.args(), cj.commutative())) : null;
            case MirInst.CallMethod cm -> cm.effect().isPureFloatable() && cm.targetFunctionId() >= 0
                    ? new CallMethodKey(cm.receiver().id(), cm.targetFunctionId(), cm.result().kind(),
                    canonicalArgs(cm.args(), cm.commutative())) : null;
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
        return op == HirExpr.BinaryOp.ADD || op == HirExpr.BinaryOp.MUL
                || op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ;
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

    private enum NullMarker { INSTANCE }
    private record DoubleBits(long bits) {}
    private record FloatBits(int bits) {}
    private record ConstKey(PrimitiveKind kind, Object value) {}
    private record UnaryKey(HirExpr.UnaryOp op, PrimitiveKind kind, int operandId) {}
    private record BinaryKey(HirExpr.BinaryOp op, boolean stringConcat, PrimitiveKind kind, int leftId, int rightId) {}
    private record ConvertKey(PrimitiveKind targetKind, int operandId) {}
    private record WidenKey(int rank, PrimitiveKind kind, int operandId) {}
    private record FieldKey(int recvId, int classId, int memberIdx, String name, PrimitiveKind kind, int epoch) {}
    private record JavaFieldKey(int recvId, Field field, PrimitiveKind kind, int epoch) {}
    private record ArrayLenKey(int arrayId) {}
    private record CallBabbleKey(int fnId, PrimitiveKind kind, List<Integer> argIds) {}
    private record CallJavaKey(int recvId, Method method, PrimitiveKind kind, List<Integer> argIds) {}
    private record CallMethodKey(int recvId, int fnId, PrimitiveKind kind, List<Integer> argIds) {}
}
