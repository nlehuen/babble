package org.babblelang.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirVar;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Per-method bytecode emission state passed across {@link HirEmitter} and its specialized
 * sub-emitters, replacing the 23 mutable per-method cursor fields formerly stored directly on
 * {@link HirEmitter} (BIP027-I2).
 */
final class MethodEmitContext {
    record LoopTarget(Label breakLabel, Label continueLabel, int resultSlot) {}

    record TailLoop(Label head, int[] paramSlots, int maskSlot) {}

    /**
     * Pre-evaluated SSA operand slot injected by {@link MirEmitter} when delegating ABI emission to
     * {@link HirEmitter}.
     */
    record MirOperand(
            int slot, PrimitiveKind kind, boolean isConstant, Object constant, boolean isStacked) {
        MirOperand(int slot, PrimitiveKind kind) {
            this(slot, kind, false, null, false);
        }

        /** A rematerialized constant : pushed where the operand is read, in no local. */
        static MirOperand constant(Object value, PrimitiveKind kind) {
            return new MirOperand(-1, kind, true, value, false);
        }

        /**
         * A single-use value left on the operand stack by the immediately preceding MIR
         * instruction.
         */
        static MirOperand stacked(PrimitiveKind kind) {
            return new MirOperand(-1, kind, false, null, true);
        }
    }

    static final class ActiveFinally {
        final org.babblelang.ir.hir.HirExpr.Try tryNode;
        final int loopDepthAtEntry;
        final boolean hasFinally;
        final List<Label> rangeStarts = new ArrayList<>();
        final List<Label> rangeEnds = new ArrayList<>();
        Label currentStart;

        ActiveFinally(
                org.babblelang.ir.hir.HirExpr.Try tryNode,
                int loopDepthAtEntry,
                Label start,
                boolean hasFinally) {
            this.tryNode = tryNode;
            this.loopDepthAtEntry = loopDepthAtEntry;
            this.currentStart = start;
            this.hasFinally = hasFinally;
        }

        void splitBefore(MethodVisitor mv) {
            if (currentStart != null) {
                Label end = new Label();
                mv.visitLabel(end);
                rangeStarts.add(currentStart);
                rangeEnds.add(end);
                currentStart = null;
            }
        }

        void resumeAfter(MethodVisitor mv) {
            currentStart = new Label();
            mv.visitLabel(currentStart);
        }

        void close(MethodVisitor mv) {
            splitBefore(mv);
        }
    }

    MethodVisitor mv;
    int methodOwnerId;
    int rtLocal;
    int nsLocal;
    int nextLocal;
    int currentLine = -1;
    List<Runnable> deferred;
    Map<Integer, Integer> slots;
    Map<Integer, Integer> definedSlots;
    PrimitiveKind stackKind = PrimitiveKind.NONE;
    PrimitiveKind methodReturnKind = PrimitiveKind.NONE;
    List<HirVar> capturedVariables = List.of();
    int capturedLocal = -1;
    int staticTarget = -1;
    int selfLocal = -1;
    int thizLocal = -1;
    ClassGen methodClass;
    ClassGen constructorClass;

    /** The loop head of the function body being emitted, for its self tail calls (BIP024). */
    TailLoop tailLoop;

    int typedThisLocal = -1;
    int frameNs = -1;
    int parameterBase = -1;
    final Deque<LoopTarget> loopTargets = new ArrayDeque<>();
    final Deque<ActiveFinally> activeFinallies = new ArrayDeque<>();
    final java.util.Set<Integer> nonNegativeVars = new java.util.HashSet<>();
    Map<org.babblelang.ir.hir.HirExpr, MirOperand> mirOverrides;

    /**
     * Whether name reads go through caching {@code Bootstrap.name} sites ({@link MirEmitter}, at
     * {@code -O2}).
     */
    boolean cacheNameReads;

    void reset(
            MethodVisitor mv,
            int ownerId,
            int firstFreeLocal,
            int rt,
            int ns,
            List<HirVar> captured) {
        this.mv = mv;
        this.methodOwnerId = ownerId;
        this.rtLocal = rt;
        this.nsLocal = ns;
        this.frameNs = ns;
        this.parameterBase = -1;
        this.nextLocal = firstFreeLocal;
        this.currentLine = -1;
        this.deferred = new ArrayList<>();
        this.slots = new HashMap<>();
        this.definedSlots = new HashMap<>();
        this.stackKind = PrimitiveKind.NONE;
        this.methodReturnKind = PrimitiveKind.NONE;
        this.capturedVariables = captured != null ? captured : List.of();
        this.capturedLocal = -1;
        this.staticTarget = -1;
        this.selfLocal = HirEmitter.SELF;
        this.thizLocal = 1;
        this.methodClass = null;
        this.constructorClass = null;
        this.tailLoop = null;
        this.typedThisLocal = -1;
        this.loopTargets.clear();
        this.activeFinallies.clear();
        this.nonNegativeVars.clear();
        this.mirOverrides = null;
        this.cacheNameReads = false;
    }

    void finish() {
        for (int i = 0; i < deferred.size(); i++) {
            deferred.get(i).run();
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = null;
    }

    int newLocal() {
        return nextLocal++;
    }

    int newLocal(PrimitiveKind kind) {
        int slot = nextLocal;
        nextLocal += kind.slots();
        return slot;
    }

    void line(int lineNumber) {
        if (lineNumber > 0 && lineNumber != currentLine) {
            currentLine = lineNumber;
            Label label = new Label();
            mv.visitLabel(label);
            mv.visitLineNumber(lineNumber, label);
        }
    }

    void beginTailLoop(int[] paramSlots, int maskSlot) {
        tailLoop = new TailLoop(new Label(), paramSlots, maskSlot);
        mv.visitLabel(tailLoop.head());
    }
}
