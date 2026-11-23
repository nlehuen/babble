package org.babblelang.compiler;

import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirVar;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-method bytecode emission state passed across {@link HirEmitter} and its
 * specialized sub-emitters, replacing the 23 mutable per-method cursor fields
 * formerly stored directly on {@link HirEmitter} (BIP027-I2).
 */
final class MethodEmitContext {
    record LoopTarget(Label breakLabel, Label continueLabel, int resultSlot) {
    }

    record TailLoop(Label head, int[] paramSlots, int maskSlot) {
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

    void reset(MethodVisitor mv, int ownerId, int firstFreeLocal, int rt, int ns, List<HirVar> captured) {
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
