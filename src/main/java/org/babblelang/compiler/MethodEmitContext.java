package org.babblelang.compiler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirVar;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Per-method bytecode emission state passed across {@link HirEmitter} and its specialized
 * sub-emitters, replacing the 23 mutable per-method cursor fields formerly stored directly on
 * {@link HirEmitter} (BIP027-I2).
 */
final class MethodEmitContext {
    record LoopTarget(Label breakLabel, Label continueLabel, int resultSlot) {}

    record TailLoop(Label head, int[] paramSlots, int maskSlot) {}

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
    Map<Integer, Integer> preallocatedSlots;
    final java.util.Set<Integer> initializedSlots = new java.util.HashSet<>();
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
    final Set<Integer> nonNegativeVars = new HashSet<>();

    /**
     * Frame-local {@link HirVar} IDs already proven non-null in the current straight-line JVM basic
     * block (cleared automatically on any jump, switch, or non-line label).
     */
    final Set<Integer> checkedNonNullVars = new HashSet<>();

    private boolean emittingLineLabel;

    /** Category-segregated free lists of reusable scratch temporary JVM local slots. */
    private final int[][] freeTemps = new int[5][8];

    private final int[] freeTempCounts = new int[5];

    /** Whether name reads go through caching {@code Bootstrap.name} sites (from {@code -O1}). */
    boolean cacheNameReads;

    void reset(
            MethodVisitor rawMv,
            int ownerId,
            int firstFreeLocal,
            int rt,
            int ns,
            List<HirVar> captured) {
        this.mv =
                new MethodVisitor(Opcodes.ASM9, rawMv) {
                    @Override
                    public void visitLabel(Label label) {
                        if (!emittingLineLabel) {
                            checkedNonNullVars.clear();
                        }
                        super.visitLabel(label);
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        checkedNonNullVars.clear();
                        super.visitJumpInsn(opcode, label);
                    }

                    @Override
                    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... lbls) {
                        checkedNonNullVars.clear();
                        super.visitTableSwitchInsn(min, max, dflt, lbls);
                    }

                    @Override
                    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                        checkedNonNullVars.clear();
                        super.visitLookupSwitchInsn(dflt, keys, labels);
                    }
                };
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
        this.preallocatedSlots = null;
        this.initializedSlots.clear();
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
        this.checkedNonNullVars.clear();
        this.emittingLineLabel = false;
        java.util.Arrays.fill(this.freeTempCounts, 0);
        this.cacheNameReads = false;
    }

    void finish() {
        for (int i = 0; i < deferred.size(); i++) {
            checkedNonNullVars.clear();
            deferred.get(i).run();
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = null;
    }

    void restoreNextLocal(int saved) {
        this.nextLocal = saved;
        for (int cat = 0; cat < freeTemps.length; cat++) {
            int keep = 0;
            int n = freeTempCounts[cat];
            int[] arr = freeTemps[cat];
            for (int i = 0; i < n; i++) {
                if (arr[i] < saved) {
                    arr[keep++] = arr[i];
                }
            }
            freeTempCounts[cat] = keep;
        }
    }

    int newLocal() {
        return nextLocal++;
    }

    int newLocal(PrimitiveKind kind) {
        int slot = nextLocal;
        nextLocal += kind.slots();
        return slot;
    }

    int acquireTemp() {
        return acquireTemp(PrimitiveKind.NONE);
    }

    int acquireTemp(PrimitiveKind kind) {
        int cat = slotCategory(kind);
        int count = freeTempCounts[cat];
        if (count > 0) {
            freeTempCounts[cat] = count - 1;
            return freeTemps[cat][count - 1];
        }
        return newLocal(kind);
    }

    void releaseTemp(int slot) {
        releaseTemp(slot, PrimitiveKind.NONE);
    }

    void releaseTemp(int slot, PrimitiveKind kind) {
        int cat = slotCategory(kind);
        int count = freeTempCounts[cat];
        if (count == freeTemps[cat].length) {
            freeTemps[cat] = java.util.Arrays.copyOf(freeTemps[cat], count * 2);
        }
        freeTemps[cat][count] = slot;
        freeTempCounts[cat] = count + 1;
    }

    private static int slotCategory(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> 0;
            case FLOAT -> 1;
            case NONE -> 2;
            case LONG -> 3;
            case DOUBLE -> 4;
        };
    }

    void line(int lineNumber) {
        if (lineNumber > 0 && lineNumber != currentLine) {
            currentLine = lineNumber;
            Label label = new Label();
            emittingLineLabel = true;
            try {
                mv.visitLabel(label);
            } finally {
                emittingLineLabel = false;
            }
            mv.visitLineNumber(lineNumber, label);
        }
    }

    void beginTailLoop(int[] paramSlots, int maskSlot) {
        tailLoop = new TailLoop(new Label(), paramSlots, maskSlot);
        mv.visitLabel(tailLoop.head());
    }
}
