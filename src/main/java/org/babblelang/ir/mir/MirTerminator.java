package org.babblelang.ir.mir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Sealed basic-block terminators for SSA CFG {@code MIR} ({@code BIP014-I3} §6.4).
 *
 * <p>Every {@link MirBlock} ends with exactly one terminator. Control transfers to other blocks
 * carry explicit block-parameter arguments ({@link Br}, {@link BrIf}), replacing {@code phi} nodes.
 */
public sealed interface MirTerminator
        permits MirTerminator.Br,
                MirTerminator.BrIf,
                MirTerminator.Switch,
                MirTerminator.Ret,
                MirTerminator.Throw,
                MirTerminator.TailCall,
                MirTerminator.Unreachable {

    int line();

    /** Returns all SSA values read by this terminator. */
    List<MirVal> usedValues();

    /** Returns all normal CFG successor block IDs (excluding exception handlers). */
    int[] successorIds();

    /** Returns a terminator with all operand uses remapped through {@code fn}. */
    MirTerminator remapUses(UnaryOperator<MirVal> fn);

    private static List<MirVal> remapList(List<MirVal> vals, UnaryOperator<MirVal> fn) {
        List<MirVal> out = null;
        for (int i = 0; i < vals.size(); i++) {
            MirVal orig = vals.get(i);
            MirVal mapped = fn.apply(orig);
            if (mapped != orig && out == null) {
                out = new ArrayList<>(vals);
            }
            if (out != null) {
                out.set(i, mapped);
            }
        }
        return out != null ? out : vals;
    }

    /** Unconditional branch {@code br bbTarget(args...)}. */
    record Br(int targetBlock, List<MirVal> args, int line) implements MirTerminator {
        public Br {
            args = List.copyOf(args);
        }

        @Override
        public List<MirVal> usedValues() {
            return args;
        }

        @Override
        public int[] successorIds() {
            return new int[] {targetBlock};
        }

        @Override
        public MirTerminator remapUses(UnaryOperator<MirVal> fn) {
            List<MirVal> na = remapList(args, fn);
            return na == args ? this : new Br(targetBlock, na, line);
        }
    }

    /** Conditional branch {@code br_if cond, bbThen(thenArgs...), bbElse(elseArgs...)}. */
    record BrIf(
            MirVal cond,
            int thenBlock,
            List<MirVal> thenArgs,
            int elseBlock,
            List<MirVal> elseArgs,
            int line)
            implements MirTerminator {
        public BrIf {
            Objects.requireNonNull(cond, "cond");
            thenArgs = List.copyOf(thenArgs);
            elseArgs = List.copyOf(elseArgs);
        }

        @Override
        public List<MirVal> usedValues() {
            List<MirVal> out = new ArrayList<>(1 + thenArgs.size() + elseArgs.size());
            out.add(cond);
            out.addAll(thenArgs);
            out.addAll(elseArgs);
            return out;
        }

        @Override
        public int[] successorIds() {
            return thenBlock == elseBlock
                    ? new int[] {thenBlock}
                    : new int[] {thenBlock, elseBlock};
        }

        @Override
        public MirTerminator remapUses(UnaryOperator<MirVal> fn) {
            MirVal nc = fn.apply(cond);
            List<MirVal> nt = remapList(thenArgs, fn);
            List<MirVal> ne = remapList(elseArgs, fn);
            return (nc == cond && nt == thenArgs && ne == elseArgs)
                    ? this
                    : new BrIf(nc, thenBlock, nt, elseBlock, ne, line);
        }
    }

    /**
     * Multi-way integer switch {@code switch key [k0 -> bb0(args...), ...] default ->
     * bbD(defaultArgs...)} ({@code BIP038-D10}).
     */
    record Switch(
            MirVal key,
            int[] keys,
            int[] targetBlocks,
            List<List<MirVal>> targetArgs,
            int defaultBlock,
            List<MirVal> defaultArgs,
            int line)
            implements MirTerminator {
        public Switch {
            Objects.requireNonNull(key, "key");
            keys = keys.clone();
            targetBlocks = targetBlocks.clone();
            if (keys.length != targetBlocks.length || keys.length != targetArgs.size()) {
                throw new IllegalArgumentException("Switch keys/targets/args length mismatch");
            }
            List<List<MirVal>> copy = new ArrayList<>(targetArgs.size());
            for (List<MirVal> a : targetArgs) {
                copy.add(List.copyOf(a));
            }
            targetArgs = List.copyOf(copy);
            defaultArgs = List.copyOf(defaultArgs);
        }

        @Override
        public List<MirVal> usedValues() {
            List<MirVal> out = new ArrayList<>(1 + defaultArgs.size() + keys.length);
            out.add(key);
            for (List<MirVal> args : targetArgs) {
                out.addAll(args);
            }
            out.addAll(defaultArgs);
            return out;
        }

        @Override
        public int[] successorIds() {
            java.util.LinkedHashSet<Integer> uniq =
                    new java.util.LinkedHashSet<>(targetBlocks.length + 1);
            for (int tb : targetBlocks) {
                uniq.add(tb);
            }
            uniq.add(defaultBlock);
            int[] res = new int[uniq.size()];
            int i = 0;
            for (int id : uniq) {
                res[i++] = id;
            }
            return res;
        }

        @Override
        public MirTerminator remapUses(UnaryOperator<MirVal> fn) {
            MirVal nk = fn.apply(key);
            List<List<MirVal>> nta = null;
            for (int i = 0; i < targetArgs.size(); i++) {
                List<MirVal> orig = targetArgs.get(i);
                List<MirVal> mapped = remapList(orig, fn);
                if (mapped != orig) {
                    if (nta == null) {
                        nta = new ArrayList<>(targetArgs);
                    }
                    nta.set(i, mapped);
                }
            }
            List<MirVal> nda = remapList(defaultArgs, fn);
            return (nk == key && nta == null && nda == defaultArgs)
                    ? this
                    : new Switch(
                            nk,
                            keys,
                            targetBlocks,
                            nta != null ? nta : targetArgs,
                            defaultBlock,
                            nda,
                            line);
        }
    }

    /** Function return {@code ret value}. */
    record Ret(MirVal value, PrimitiveKind returnKind, int wideningRank, int line)
            implements MirTerminator {
        public Ret {
            Objects.requireNonNull(value, "value");
            returnKind = returnKind != null ? returnKind : PrimitiveKind.NONE;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(value);
        }

        @Override
        public int[] successorIds() {
            return new int[0];
        }

        @Override
        public MirTerminator remapUses(UnaryOperator<MirVal> fn) {
            MirVal nv = fn.apply(value);
            return nv == value ? this : new Ret(nv, returnKind, wideningRank, line);
        }
    }

    /** Explicit {@code throw value} ({@code BIP028}). */
    record Throw(MirVal value, boolean isJavaThrowable, int line) implements MirTerminator {
        public Throw {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(value);
        }

        @Override
        public int[] successorIds() {
            return new int[0];
        }

        @Override
        public MirTerminator remapUses(UnaryOperator<MirVal> fn) {
            MirVal nv = fn.apply(value);
            return nv == value ? this : new Throw(nv, isJavaThrowable, line);
        }
    }

    /** Self tail call jumping back to the function prologue with new arguments ({@code BIP024}). */
    record TailCall(
            int functionId,
            List<MirVal> args,
            List<String> argNames,
            LoweringTargets.ArgumentBinding binding,
            int line)
            implements MirTerminator {
        public TailCall {
            args = List.copyOf(args);
            argNames = java.util.Collections.unmodifiableList(new ArrayList<>(argNames));
        }

        @Override
        public List<MirVal> usedValues() {
            return args;
        }

        @Override
        public int[] successorIds() {
            return new int[0];
        }

        @Override
        public MirTerminator remapUses(UnaryOperator<MirVal> fn) {
            List<MirVal> na = remapList(args, fn);
            return na == args ? this : new TailCall(functionId, na, argNames, binding, line);
        }
    }

    /** Unreachable terminator (placed after dead/exhausted control paths). */
    record Unreachable(int line) implements MirTerminator {
        @Override
        public List<MirVal> usedValues() {
            return List.of();
        }

        @Override
        public int[] successorIds() {
            return new int[0];
        }

        @Override
        public MirTerminator remapUses(UnaryOperator<MirVal> fn) {
            return this;
        }
    }
}
