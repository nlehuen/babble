package org.babblelang.opt.mir;

import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirHandler;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirModule;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;
import org.babblelang.opt.PassContext;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sparse Conditional Constant Propagation ({@code O1} + {@code O4}, Wegman–Zadeck {@code [WZ91]})
 * over SSA {@link MirModule} ({@code BIP014-I3} §6.4).
 *
 * <p>Jointly evaluates SSA values and CFG edge reachability over the 3-level lattice
 * ({@code TOP} &lt; {@code CONST(v)} &lt; {@code BOTTOM}), folding scalar arithmetic/comparisons,
 * conversions, string concatenation, {@code BuiltinAxioms}-foldable static JDK calls, and
 * constant {@link MirTerminator.BrIf} branches into unconditional {@link MirTerminator.Br} edges
 * while dropping unreachable blocks.
 */
public final class SccpPass implements MirPass {
    @Override
    public String name() {
        return "SccpPass";
    }

    @Override
    public Result run(MirModule module, PassContext context) {
        int totalRewrites = 0;
        Map<Integer, MirFunction> updated = new LinkedHashMap<>(module.functions().size() * 2);
        for (Map.Entry<Integer, MirFunction> e : module.functions().entrySet()) {
            FnResult r = runOnFunction(e.getValue());
            updated.put(e.getKey(), r.fn);
            totalRewrites += r.rewrites;
        }
        return totalRewrites == 0 ? Result.unchanged(module) : new Result(module.withFunctions(updated), totalRewrites);
    }

    private record FnResult(MirFunction fn, int rewrites) {
    }

    private enum State { TOP, CONST, BOTTOM }

    private record Lattice(State state, Object value) {
        static final Lattice TOP = new Lattice(State.TOP, null);
        static final Lattice BOTTOM = new Lattice(State.BOTTOM, null);

        static Lattice constant(Object v) {
            return new Lattice(State.CONST, v);
        }

        Lattice meet(Lattice other) {
            if (this.state == State.TOP) return other;
            if (other.state == State.TOP) return this;
            if (this.state == State.BOTTOM || other.state == State.BOTTOM) return BOTTOM;
            return sameConst(this.value, other.value) ? this : BOTTOM;
        }
    }

    private static boolean sameConst(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null || a.getClass() != b.getClass()) return false;
        if (a instanceof Double da && b instanceof Double db) {
            return Double.doubleToRawLongBits(da) == Double.doubleToRawLongBits(db);
        }
        if (a instanceof Float fa && b instanceof Float fb) {
            return Float.floatToRawIntBits(fa) == Float.floatToRawIntBits(fb);
        }
        return a.equals(b);
    }

    private record EdgeKey(int fromBlock, int toBlock, boolean isElseEdge) {
    }

    private static FnResult runOnFunction(MirFunction fn) {
        Map<Integer, MirBlock> blockMap = fn.blockMap();
        Map<Integer, Lattice> lattice = new HashMap<>();
        Map<Integer, List<Integer>> valUseBlocks = new HashMap<>();

        for (MirVal p : fn.params()) {
            lattice.put(p.id(), Lattice.BOTTOM);
        }
        for (MirBlock b : fn.blocks()) {
            for (MirVal bp : b.params()) {
                lattice.put(bp.id(), b.handlerEntry() ? Lattice.BOTTOM : Lattice.TOP);
            }
            for (MirInst inst : b.insts()) {
                if (inst.result() != null) {
                    lattice.put(inst.result().id(), Lattice.TOP);
                }
                for (MirVal u : inst.usedValues()) {
                    valUseBlocks.computeIfAbsent(u.id(), k -> new ArrayList<>()).add(b.id());
                }
            }
            for (MirVal u : b.terminator().usedValues()) {
                valUseBlocks.computeIfAbsent(u.id(), k -> new ArrayList<>()).add(b.id());
            }
        }

        // Track incoming edges per block for block-parameter meet evaluation.
        Map<Integer, List<EdgeInfo>> incomingEdges = new HashMap<>();
        for (MirBlock b : fn.blocks()) {
            incomingEdges.put(b.id(), new ArrayList<>());
        }
        for (MirBlock b : fn.blocks()) {
            if (b.terminator() instanceof MirTerminator.Br br) {
                incomingEdges.get(br.targetBlock()).add(new EdgeInfo(new EdgeKey(b.id(), br.targetBlock(), false), br.args()));
            } else if (b.terminator() instanceof MirTerminator.BrIf bi) {
                incomingEdges.get(bi.thenBlock()).add(new EdgeInfo(new EdgeKey(b.id(), bi.thenBlock(), false), bi.thenArgs()));
                incomingEdges.get(bi.elseBlock()).add(new EdgeInfo(new EdgeKey(b.id(), bi.elseBlock(), true), bi.elseArgs()));
            }
        }

        Set<Integer> execBlocks = new HashSet<>();
        Set<EdgeKey> execEdges = new HashSet<>();
        Deque<Integer> blockWorklist = new BlockWorklist();

        markBlockExec(fn.entryBlockId(), execBlocks, blockWorklist, blockMap, lattice);

        while (!blockWorklist.isEmpty()) {
            int bid = blockWorklist.poll();
            MirBlock b = blockMap.get(bid);
            if (b == null) continue;

            // 1. Re-evaluate block parameters from executable incoming edges.
            if (!b.handlerEntry() && !b.params().isEmpty()) {
                List<EdgeInfo> edges = incomingEdges.get(bid);
                for (int i = 0; i < b.params().size(); i++) {
                    MirVal bp = b.params().get(i);
                    Lattice acc = Lattice.TOP;
                    for (EdgeInfo ei : edges) {
                        if (execEdges.contains(ei.key)) {
                            acc = acc.meet(lattice.getOrDefault(ei.args.get(i).id(), Lattice.TOP));
                        }
                    }
                    updateLattice(bp.id(), acc, lattice, execBlocks, valUseBlocks, blockWorklist);
                }
            }

            // 2. Evaluate instructions.
            for (MirInst inst : b.insts()) {
                MirVal res = inst.result();
                if (res == null) continue;
                Lattice val = evalInst(inst, lattice);
                updateLattice(res.id(), val, lattice, execBlocks, valUseBlocks, blockWorklist);
            }

            // 3. Evaluate terminator & propagate executable edges.
            MirTerminator term = b.terminator();
            if (term instanceof MirTerminator.Br br) {
                markEdgeExec(new EdgeKey(bid, br.targetBlock(), false), execEdges, execBlocks, blockWorklist, blockMap, lattice);
            } else if (term instanceof MirTerminator.BrIf bi) {
                Lattice cl = lattice.getOrDefault(bi.cond().id(), Lattice.TOP);
                if (cl.state == State.CONST) {
                    boolean truthy = isTruthy(cl.value);
                    if (truthy) {
                        markEdgeExec(new EdgeKey(bid, bi.thenBlock(), false), execEdges, execBlocks, blockWorklist, blockMap, lattice);
                    } else {
                        markEdgeExec(new EdgeKey(bid, bi.elseBlock(), true), execEdges, execBlocks, blockWorklist, blockMap, lattice);
                    }
                } else if (cl.state == State.BOTTOM) {
                    markEdgeExec(new EdgeKey(bid, bi.thenBlock(), false), execEdges, execBlocks, blockWorklist, blockMap, lattice);
                    markEdgeExec(new EdgeKey(bid, bi.elseBlock(), true), execEdges, execBlocks, blockWorklist, blockMap, lattice);
                }
            }
        }

        // Rewrite blocks: keep only executable blocks, fold constant pure instructions and constant BrIfs.
        int rewrites = 0;
        List<MirBlock> outBlocks = new ArrayList<>(execBlocks.size());
        for (MirBlock b : fn.blocks()) {
            if (!execBlocks.contains(b.id())) {
                rewrites++;
                continue;
            }
            List<MirInst> newInsts = new ArrayList<>(b.insts().size());
            for (MirInst inst : b.insts()) {
                MirVal res = inst.result();
                if (res != null && !(inst instanceof MirInst.Const) && !(inst instanceof MirInst.Narrow)
                        && inst.effect().isRemovableIfUnused()) {
                    Lattice l = lattice.get(res.id());
                    if (l != null && l.state == State.CONST && isCompatibleConst(l.value, res.kind())) {
                        newInsts.add(new MirInst.Const(res, l.value, inst.line()));
                        rewrites++;
                        continue;
                    }
                }
                newInsts.add(inst);
            }
            MirTerminator term = b.terminator();
            if (term instanceof MirTerminator.BrIf bi) {
                Lattice cl = lattice.getOrDefault(bi.cond().id(), Lattice.BOTTOM);
                if (cl.state == State.CONST) {
                    boolean truthy = isTruthy(cl.value);
                    term = truthy
                            ? new MirTerminator.Br(bi.thenBlock(), bi.thenArgs(), bi.line())
                            : new MirTerminator.Br(bi.elseBlock(), bi.elseArgs(), bi.line());
                    rewrites++;
                }
            }
            outBlocks.add(new MirBlock(b.id(), b.params(), newInsts, term, b.handlers(), b.handlerEntry()));
        }
        return rewrites == 0 ? new FnResult(fn, 0)
                : new FnResult(fn.withBlocks(outBlocks, fn.nextValId(), fn.nextBlockId()), rewrites);
    }

    private record EdgeInfo(EdgeKey key, List<MirVal> args) {
    }

    /**
     * The blocks to evaluate again, each at most once until it is : a block whose inputs change
     * several times before its turn is evaluated once, with all of them (the lattice is monotone,
     * so the fixed point does not depend on the order).
     */
    private static final class BlockWorklist extends ArrayDeque<Integer> {
        private final Set<Integer> queued = new HashSet<>();

        @Override
        public boolean add(Integer blockId) {
            return queued.add(blockId) && super.add(blockId);
        }

        @Override
        public Integer poll() {
            Integer blockId = super.poll();
            if (blockId != null) {
                queued.remove(blockId);
            }
            return blockId;
        }
    }

    private static void markBlockExec(int blockId, Set<Integer> execBlocks, Deque<Integer> worklist,
                                      Map<Integer, MirBlock> blockMap, Map<Integer, Lattice> lattice) {
        if (execBlocks.add(blockId)) {
            worklist.add(blockId);
            MirBlock b = blockMap.get(blockId);
            if (b != null) {
                for (MirHandler h : b.handlers()) {
                    markBlockExec(h.handlerBlockId(), execBlocks, worklist, blockMap, lattice);
                }
            }
        }
    }

    private static void markEdgeExec(EdgeKey edge, Set<EdgeKey> execEdges, Set<Integer> execBlocks,
                                     Deque<Integer> worklist, Map<Integer, MirBlock> blockMap,
                                     Map<Integer, Lattice> lattice) {
        if (execEdges.add(edge)) {
            if (!execBlocks.contains(edge.toBlock)) {
                markBlockExec(edge.toBlock, execBlocks, worklist, blockMap, lattice);
            } else {
                worklist.add(edge.toBlock);
            }
        }
    }

    private static void updateLattice(int valId, Lattice next, Map<Integer, Lattice> lattice,
                                      Set<Integer> execBlocks, Map<Integer, List<Integer>> valUseBlocks,
                                      Deque<Integer> blockWorklist) {
        Lattice prev = lattice.getOrDefault(valId, Lattice.TOP);
        Lattice merged = prev.meet(next);
        if (!Objects.equals(prev, merged)) {
            lattice.put(valId, merged);
            List<Integer> users = valUseBlocks.get(valId);
            if (users != null) {
                for (int ub : users) {
                    if (execBlocks.contains(ub)) {
                        blockWorklist.add(ub);
                    }
                }
            }
        }
    }

    private static Lattice evalInst(MirInst inst, Map<Integer, Lattice> lat) {
        return switch (inst) {
            case MirInst.Const c -> Lattice.constant(c.value());
            case MirInst.Copy c -> lat.getOrDefault(c.source().id(), Lattice.TOP);
            case MirInst.Move m -> lat.getOrDefault(m.operand().id(), Lattice.TOP);
            case MirInst.Freeze f -> lat.getOrDefault(f.operand().id(), Lattice.TOP);
            case MirInst.Narrow ignored -> Lattice.BOTTOM; // Preserve narrowing anchors
            case MirInst.Unary u -> evalUnary(u, lat);
            case MirInst.Binary b -> evalBinary(b, lat);
            case MirInst.Convert c -> evalConvert(c, lat);
            case MirInst.CallJava cj -> evalCallJava(cj, lat);
            default -> Lattice.BOTTOM;
        };
    }

    private static Lattice evalUnary(MirInst.Unary u, Map<Integer, Lattice> lat) {
        Lattice op = lat.getOrDefault(u.operand().id(), Lattice.TOP);
        if (op.state != State.CONST) return op;
        if (u.op() == HirExpr.UnaryOp.NOT) {
            return Lattice.constant(!isTruthy(op.value));
        }
        Object v = op.value;
        return switch (u.result().kind()) {
            case INT -> v instanceof Number n ? Lattice.constant(-n.intValue()) : Lattice.BOTTOM;
            case LONG -> v instanceof Number n ? Lattice.constant(-n.longValue()) : Lattice.BOTTOM;
            case FLOAT -> v instanceof Number n ? Lattice.constant(-n.floatValue()) : Lattice.BOTTOM;
            case DOUBLE -> v instanceof Number n ? Lattice.constant(-n.doubleValue()) : Lattice.BOTTOM;
            default -> Lattice.BOTTOM;
        };
    }

    private static Lattice evalBinary(MirInst.Binary b, Map<Integer, Lattice> lat) {
        Lattice ll = lat.getOrDefault(b.left().id(), Lattice.TOP);
        Lattice rl = lat.getOrDefault(b.right().id(), Lattice.TOP);
        if (ll.state == State.BOTTOM || rl.state == State.BOTTOM) return Lattice.BOTTOM;
        if (ll.state == State.TOP || rl.state == State.TOP) return Lattice.TOP;

        Object lv = ll.value;
        Object rv = rl.value;
        if (b.stringConcat() && lv instanceof String ls && rv instanceof String rs) {
            return Lattice.constant(ls + rs);
        }
        PrimitiveKind out = b.result().kind();
        if (b.op().isComparison() && out == PrimitiveKind.BOOL) {
            return evalComparison(b.op(), b.left().kind(), b.right().kind(), lv, rv);
        }
        if (!(lv instanceof Number ln) || !(rv instanceof Number rn)) {
            return Lattice.BOTTOM;
        }
        return switch (out) {
            case INT -> switch (b.op()) {
                case ADD -> Lattice.constant(ln.intValue() + rn.intValue());
                case SUB -> Lattice.constant(ln.intValue() - rn.intValue());
                case MUL -> Lattice.constant(ln.intValue() * rn.intValue());
                default -> Lattice.BOTTOM;
            };
            case LONG -> switch (b.op()) {
                case ADD -> Lattice.constant(ln.longValue() + rn.longValue());
                case SUB -> Lattice.constant(ln.longValue() - rn.longValue());
                case MUL -> Lattice.constant(ln.longValue() * rn.longValue());
                default -> Lattice.BOTTOM;
            };
            case FLOAT -> switch (b.op()) {
                case ADD -> Lattice.constant(ln.floatValue() + rn.floatValue());
                case SUB -> Lattice.constant(ln.floatValue() - rn.floatValue());
                case MUL -> Lattice.constant(ln.floatValue() * rn.floatValue());
                case DIV -> Lattice.constant(ln.floatValue() / rn.floatValue());
                default -> Lattice.BOTTOM;
            };
            case DOUBLE -> switch (b.op()) {
                case ADD -> Lattice.constant(ln.doubleValue() + rn.doubleValue());
                case SUB -> Lattice.constant(ln.doubleValue() - rn.doubleValue());
                case MUL -> Lattice.constant(ln.doubleValue() * rn.doubleValue());
                case DIV -> Lattice.constant(ln.doubleValue() / rn.doubleValue());
                default -> Lattice.BOTTOM;
            };
            default -> Lattice.BOTTOM;
        };
    }

    private static Lattice evalComparison(HirExpr.BinaryOp op, PrimitiveKind lk, PrimitiveKind rk, Object lv, Object rv) {
        if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL && lv instanceof Boolean lb && rv instanceof Boolean rb) {
            return switch (op) {
                case EQ -> Lattice.constant(lb.booleanValue() == rb.booleanValue());
                case NEQ -> Lattice.constant(lb.booleanValue() != rb.booleanValue());
                default -> Lattice.BOTTOM;
            };
        }
        if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR && lv instanceof Character lc && rv instanceof Character rc) {
            return Lattice.constant(applyCmp(op, Character.compare(lc, rc)));
        }
        if (lv instanceof Number ln && rv instanceof Number rn) {
            int cmp;
            if (lk == PrimitiveKind.DOUBLE || rk == PrimitiveKind.DOUBLE) {
                cmp = Double.compare(ln.doubleValue(), rn.doubleValue());
            } else if (lk == PrimitiveKind.FLOAT || rk == PrimitiveKind.FLOAT) {
                cmp = Float.compare(ln.floatValue(), rn.floatValue());
            } else if (lk == PrimitiveKind.LONG || rk == PrimitiveKind.LONG) {
                cmp = Long.compare(ln.longValue(), rn.longValue());
            } else {
                cmp = Integer.compare(ln.intValue(), rn.intValue());
            }
            return Lattice.constant(applyCmp(op, cmp));
        }
        if (lk == PrimitiveKind.NONE && rk == PrimitiveKind.NONE && (lv == null || rv == null)) {
            return switch (op) {
                case EQ -> Lattice.constant(lv == rv);
                case NEQ -> Lattice.constant(lv != rv);
                default -> Lattice.BOTTOM;
            };
        }
        return Lattice.BOTTOM;
    }

    private static boolean applyCmp(HirExpr.BinaryOp op, int cmp) {
        return switch (op) {
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            case EQ -> cmp == 0;
            case NEQ -> cmp != 0;
            case GTE -> cmp >= 0;
            case GT -> cmp > 0;
            default -> false;
        };
    }

    private static Lattice evalConvert(MirInst.Convert c, Map<Integer, Lattice> lat) {
        Lattice op = lat.getOrDefault(c.operand().id(), Lattice.TOP);
        if (op.state != State.CONST) return op;
        Object v = op.value;
        if (c.targetKind() == PrimitiveKind.NONE) {
            return Lattice.constant(v);
        }
        if (v == null) return Lattice.BOTTOM;
        return switch (c.targetKind()) {
            case BYTE -> v instanceof Number n ? Lattice.constant(n.byteValue()) : Lattice.BOTTOM;
            case SHORT -> v instanceof Number n ? Lattice.constant(n.shortValue()) : Lattice.BOTTOM;
            case INT -> v instanceof Number n ? Lattice.constant(n.intValue()) : Lattice.BOTTOM;
            case LONG -> v instanceof Number n ? Lattice.constant(n.longValue()) : Lattice.BOTTOM;
            case FLOAT -> v instanceof Number n ? Lattice.constant(n.floatValue()) : Lattice.BOTTOM;
            case DOUBLE -> v instanceof Number n ? Lattice.constant(n.doubleValue()) : Lattice.BOTTOM;
            case BOOL -> v instanceof Boolean ? Lattice.constant(v) : Lattice.BOTTOM;
            case CHAR -> v instanceof Character ? Lattice.constant(v) : Lattice.BOTTOM;
            case NONE -> Lattice.constant(v);
        };
    }

    private static Lattice evalCallJava(MirInst.CallJava cj, Map<Integer, Lattice> lat) {
        Method m = cj.target().method();
        if (!cj.target().isStatic() || !cj.pureStaticReceiver() || cj.target().varArgs()
                || !BuiltinAxioms.current().isFoldable(m)) {
            return Lattice.BOTTOM;
        }
        Class<?>[] pTypes = m.getParameterTypes();
        if (pTypes.length != cj.args().size()) {
            return Lattice.BOTTOM;
        }
        Object[] javaArgs = new Object[pTypes.length];
        for (int i = 0; i < pTypes.length; i++) {
            Lattice al = lat.getOrDefault(cj.args().get(i).id(), Lattice.TOP);
            if (al.state != State.CONST) return al;
            if (al.value == null) return Lattice.BOTTOM;
            Object coerced = coerceJavaArg(al.value, pTypes[i]);
            if (coerced == null) return Lattice.BOTTOM;
            javaArgs[i] = coerced;
        }
        try {
            Object res = m.invoke(null, javaArgs);
            return isCompatibleConst(res, cj.result().kind()) ? Lattice.constant(res) : Lattice.BOTTOM;
        } catch (Throwable ignored) {
            return Lattice.BOTTOM;
        }
    }

    private static Object coerceJavaArg(Object val, Class<?> paramType) {
        if (paramType == int.class && val instanceof Number n) return n.intValue();
        if (paramType == long.class && val instanceof Number n) return n.longValue();
        if (paramType == float.class && val instanceof Number n) return n.floatValue();
        if (paramType == double.class && val instanceof Number n) return n.doubleValue();
        if (paramType == boolean.class && val instanceof Boolean b) return b;
        if (paramType == char.class && val instanceof Character c) return c;
        if (paramType.isInstance(val)) return val;
        return null;
    }

    private static boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        if (v instanceof Character c) return c != 0;
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    private static boolean isCompatibleConst(Object v, PrimitiveKind kind) {
        if (kind == PrimitiveKind.NONE) {
            return v == null || v instanceof String || v instanceof Boolean || v instanceof Number || v instanceof Character;
        }
        return switch (kind) {
            case BYTE -> v instanceof Byte;
            case SHORT -> v instanceof Short;
            case INT -> v instanceof Integer;
            case LONG -> v instanceof Long;
            case FLOAT -> v instanceof Float;
            case DOUBLE -> v instanceof Double;
            case BOOL -> v instanceof Boolean;
            case CHAR -> v instanceof Character;
            case NONE -> true;
        };
    }
}
