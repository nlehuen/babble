package org.babblelang.ir.mir;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.CallSite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Sealed hierarchy of SSA instructions inside a {@link MirBlock} ({@code BIP014-I3} §6.4).
 *
 * <p>Every instruction is an immutable Java 21 {@code record} carrying an optional SSA result
 * {@link MirVal}, source {@link #line()}, and {@link MirEffect} summary. Passes rewrite operand
 * references uniformly via {@link #remapUses(UnaryOperator)}.
 */
public sealed interface MirInst permits
        MirInst.Const,
        MirInst.Copy,
        MirInst.Unary,
        MirInst.Binary,
        MirInst.Widen,
        MirInst.Convert,
        MirInst.Narrow,
        MirInst.CheckCast,
        MirInst.NonNull,
        MirInst.IsCheck,
        MirInst.LoadField,
        MirInst.StoreField,
        MirInst.LoadJavaField,
        MirInst.StoreJavaField,
        MirInst.ArrayGet,
        MirInst.ArraySet,
        MirInst.ArrayLen,
        MirInst.CallBabble,
        MirInst.CallMethod,
        MirInst.CallInterface,
        MirInst.CallJava,
        MirInst.CallJavaCtor,
        MirInst.RecordNew,
        MirInst.RecordReuse,
        MirInst.Move,
        MirInst.Freeze,
        MirInst.SlotDecl,
        MirInst.SlotRead,
        MirInst.SlotWrite,
        MirInst.ScopeEnter,
        MirInst.ScopeExit,
        MirInst.ForIterInit,
        MirInst.ForIterHasNext,
        MirInst.ForIterNext,
        MirInst.UnwrapThrown,
        MirInst.CatchMatch,
        MirInst.AddSuppressed,
        MirInst.OpaqueExpr {

    /** SSA value defined by this instruction, or {@code null} for void instructions. */
    MirVal result();

    int line();

    MirEffect effect();

    /** All SSA values read by this instruction. */
    List<MirVal> usedValues();

    /** Returns an instruction with all operand uses remapped through {@code fn} (identity if unchanged). */
    MirInst remapUses(UnaryOperator<MirVal> fn);

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

    enum NarrowKind {
        NON_NULL,
        IS_TYPE
    }

    enum SlotOpKind {
        INIT,
        ASSIGN
    }

    // ---------------------------------------------------------------- constants & copies

    record Const(MirVal result, Object value, int line) implements MirInst {
        public Const {
            Objects.requireNonNull(result, "result");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of();
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            return this;
        }
    }

    record Copy(MirVal result, MirVal source, int line) implements MirInst {
        public Copy {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(source);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal ns = fn.apply(source);
            return ns == source ? this : new Copy(result, ns, line);
        }
    }

    // ---------------------------------------------------------------- scalar & type ops

    record Unary(
            MirVal result,
            HirExpr.UnaryOp op,
            MirVal operand,
            String operandText,
            int line,
            MirEffect effect
    ) implements MirInst {
        public Unary {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this : new Unary(result, op, no, operandText, line, effect);
        }
    }

    record Binary(
            MirVal result,
            HirExpr.BinaryOp op,
            MirVal left,
            MirVal right,
            String leftText,
            String rightText,
            boolean stringConcat,
            int line,
            MirEffect effect
    ) implements MirInst {
        public Binary {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(left, right);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nl = fn.apply(left);
            MirVal nr = fn.apply(right);
            return (nl == left && nr == right) ? this
                    : new Binary(result, op, nl, nr, leftText, rightText, stringConcat, line, effect);
        }
    }

    /** Numeric widening ({@code wideningRank} 1..5: short, int, long, float, double). */
    record Widen(MirVal result, MirVal operand, int wideningRank, int line) implements MirInst {
        public Widen {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this : new Widen(result, no, wideningRank, line);
        }
    }

    /** Boxing / unboxing / primitive-kind adaptation ({@code convertStack(from, to)}). */
    record Convert(MirVal result, MirVal operand, PrimitiveKind targetKind, int line) implements MirInst {
        public Convert {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(targetKind, "targetKind");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this : new Convert(result, no, targetKind, line);
        }
    }

    /**
     * SSA narrowing anchor ({@code BIP007-D1}, {@code BIP014} §6.4): binds a value refined by a
     * dominating guard ({@code != null} or {@code is T}) so no pass can hoist guarded operations
     * above their guard. Emits zero runtime instructions (alias of {@link #operand()}).
     */
    record Narrow(
            MirVal result,
            NarrowKind narrowKind,
            MirVal operand,
            String targetDesc,
            int line
    ) implements MirInst {
        public Narrow {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(narrowKind, "narrowKind");
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this : new Narrow(result, narrowKind, no, targetDesc, line);
        }
    }

    /** Runtime type check ({@code as}, {@code checkedType}, or Java SAM cast). */
    record CheckCast(
            MirVal result,
            MirVal operand,
            LoweringTargets.IsTarget target,
            LoweringTargets.JavaSamTarget samTarget,
            String descriptor,
            int line
    ) implements MirInst {
        public CheckCast {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_MAY_THROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this : new CheckCast(result, no, target, samTarget, descriptor, line);
        }
    }

    /** Non-null assertion {@code e!} ({@code BIP011}). */
    record NonNull(MirVal result, MirVal operand, String expectedType, int line) implements MirInst {
        public NonNull {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_MAY_THROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this : new NonNull(result, no, expectedType, line);
        }
    }

    /** Type predicate {@code x is T}. */
    record IsCheck(
            MirVal result,
            MirVal operand,
            String builtin,
            LoweringTargets.IsTarget target,
            List<LoweringTargets.IsTarget> intersectionTargets,
            String descriptor,
            int line
    ) implements MirInst {
        public IsCheck {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(operand, "operand");
            intersectionTargets = intersectionTargets == null ? List.of() : List.copyOf(intersectionTargets);
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this
                    : new IsCheck(result, no, builtin, target, intersectionTargets, descriptor, line);
        }
    }

    // ---------------------------------------------------------------- fields & arrays

    record LoadField(
            MirVal result,
            MirVal receiver,
            String receiverText,
            int classId,
            String externalClassJvmName,
            int memberIndex,
            String memberName,
            PrimitiveKind fieldKind,
            boolean recordField,
            boolean definitelyDefined,
            int line,
            MirEffect effect
    ) implements MirInst {
        public LoadField {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(receiver);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nr = fn.apply(receiver);
            return nr == receiver ? this
                    : new LoadField(result, nr, receiverText, classId, externalClassJvmName, memberIndex,
                    memberName, fieldKind, recordField, definitelyDefined, line, effect);
        }
    }

    record StoreField(
            MirVal receiver,
            int classId,
            int memberIndex,
            String memberName,
            MirVal value,
            String targetText,
            String valueText,
            int line,
            MirEffect effect
    ) implements MirInst {
        public StoreField {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public MirVal result() {
            return null;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(receiver, value);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nr = fn.apply(receiver);
            MirVal nv = fn.apply(value);
            return (nr == receiver && nv == value) ? this
                    : new StoreField(nr, classId, memberIndex, memberName, nv, targetText, valueText, line, effect);
        }
    }

    record LoadJavaField(
            MirVal result,
            MirVal receiver,
            String receiverText,
            boolean pureStaticReceiver,
            LoweringTargets.JavaFieldTarget target,
            int line,
            MirEffect effect
    ) implements MirInst {
        public LoadJavaField {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return receiver != null ? List.of(receiver) : List.of();
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            if (receiver == null) return this;
            MirVal nr = fn.apply(receiver);
            return nr == receiver ? this
                    : new LoadJavaField(result, nr, receiverText, pureStaticReceiver, target, line, effect);
        }
    }

    record StoreJavaField(
            MirVal receiver,
            String receiverText,
            boolean pureStaticReceiver,
            LoweringTargets.JavaFieldTarget target,
            MirVal value,
            String targetText,
            String valueText,
            int line,
            MirEffect effect
    ) implements MirInst {
        public StoreJavaField {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public MirVal result() {
            return null;
        }

        @Override
        public List<MirVal> usedValues() {
            return receiver != null ? List.of(receiver, value) : List.of(value);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nr = receiver != null ? fn.apply(receiver) : null;
            MirVal nv = fn.apply(value);
            return (nr == receiver && nv == value) ? this
                    : new StoreJavaField(nr, receiverText, pureStaticReceiver, target, nv, targetText, valueText, line, effect);
        }
    }

    record ArrayGet(
            MirVal result,
            MirVal array,
            MirVal index,
            String receiverText,
            PrimitiveKind elemKind,
            Class<?> arrayClass,
            Class<?> componentClass,
            int line,
            MirEffect effect
    ) implements MirInst {
        public ArrayGet {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(array, "array");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(array, index);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal na = fn.apply(array);
            MirVal ni = fn.apply(index);
            return (na == array && ni == index) ? this
                    : new ArrayGet(result, na, ni, receiverText, elemKind, arrayClass, componentClass, line, effect);
        }
    }

    record ArraySet(
            MirVal array,
            MirVal index,
            MirVal value,
            String receiverText,
            PrimitiveKind elemKind,
            Class<?> arrayClass,
            Class<?> componentClass,
            int line,
            MirEffect effect
    ) implements MirInst {
        public ArraySet {
            Objects.requireNonNull(array, "array");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public MirVal result() {
            return null;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(array, index, value);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal na = fn.apply(array);
            MirVal ni = fn.apply(index);
            MirVal nv = fn.apply(value);
            return (na == array && ni == index && nv == value) ? this
                    : new ArraySet(na, ni, nv, receiverText, elemKind, arrayClass, componentClass, line, effect);
        }
    }

    record ArrayLen(
            MirVal result,
            MirVal array,
            String receiverText,
            PrimitiveKind elemKind,
            Class<?> arrayClass,
            Class<?> componentClass,
            int line,
            MirEffect effect
    ) implements MirInst {
        public ArrayLen {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(array, "array");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(array);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal na = fn.apply(array);
            return na == array ? this
                    : new ArrayLen(result, na, receiverText, elemKind, arrayClass, componentClass, line, effect);
        }
    }

    // ---------------------------------------------------------------- calls & records

    record CallBabble(
            MirVal result,
            MirVal callee,
            boolean selfRecurse,
            int targetFunctionId,
            List<MirVal> args,
            LoweringTargets.ArgumentBinding binding,
            boolean commutative,
            boolean idempotent,
            int line,
            MirEffect effect
    ) implements MirInst {
        public CallBabble {
            Objects.requireNonNull(result, "result");
            args = List.copyOf(args);
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            if (callee == null) return args;
            List<MirVal> out = new ArrayList<>(1 + args.size());
            out.add(callee);
            out.addAll(args);
            return out;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nc = callee != null ? fn.apply(callee) : null;
            List<MirVal> na = remapList(args, fn);
            return (nc == callee && na == args) ? this
                    : new CallBabble(result, nc, selfRecurse, targetFunctionId, na, binding, commutative, idempotent, line, effect);
        }
    }

    record CallMethod(
            MirVal result,
            MirVal receiver,
            String receiverText,
            int classId,
            String externalClassJvmName,
            String classTypeId,
            String memberName,
            int overloadAltIndex,
            int targetFunctionId,
            LoweringTargets.FunctionSignature externalSignature,
            List<MirVal> args,
            List<String> argNames,
            LoweringTargets.ArgumentBinding binding,
            CallSite callSite,
            boolean commutative,
            boolean idempotent,
            int line,
            MirEffect effect
    ) implements MirInst {
        public CallMethod {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(receiver, "receiver");
            args = List.copyOf(args);
            argNames = java.util.Collections.unmodifiableList(new ArrayList<>(argNames));
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            List<MirVal> out = new ArrayList<>(1 + args.size());
            out.add(receiver);
            out.addAll(args);
            return out;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nr = fn.apply(receiver);
            List<MirVal> na = remapList(args, fn);
            return (nr == receiver && na == args) ? this
                    : new CallMethod(result, nr, receiverText, classId, externalClassJvmName, classTypeId,
                    memberName, overloadAltIndex, targetFunctionId, externalSignature, na, argNames,
                    binding, callSite, commutative, idempotent, line, effect);
        }
    }

    record CallInterface(
            MirVal result,
            MirVal receiver,
            String receiverText,
            int selectorLine,
            String interfaceTypeId,
            String memberName,
            LoweringTargets.FunctionSignature signature,
            List<MirVal> args,
            LoweringTargets.ArgumentBinding binding,
            int line,
            MirEffect effect
    ) implements MirInst {
        public CallInterface {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(receiver, "receiver");
            args = List.copyOf(args);
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            List<MirVal> out = new ArrayList<>(1 + args.size());
            out.add(receiver);
            out.addAll(args);
            return out;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nr = fn.apply(receiver);
            List<MirVal> na = remapList(args, fn);
            return (nr == receiver && na == args) ? this
                    : new CallInterface(result, nr, receiverText, selectorLine, interfaceTypeId, memberName,
                    signature, na, binding, line, effect);
        }
    }

    record CallJava(
            MirVal result,
            MirVal receiver,
            String receiverText,
            int selectorLine,
            boolean pureStaticReceiver,
            LoweringTargets.JavaCallTarget target,
            List<MirVal> args,
            boolean commutative,
            boolean idempotent,
            int line,
            MirEffect effect
    ) implements MirInst {
        public CallJava {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(target, "target");
            args = List.copyOf(args);
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            if (receiver == null) return args;
            List<MirVal> out = new ArrayList<>(1 + args.size());
            out.add(receiver);
            out.addAll(args);
            return out;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nr = receiver != null ? fn.apply(receiver) : null;
            List<MirVal> na = remapList(args, fn);
            return (nr == receiver && na == args) ? this
                    : new CallJava(result, nr, receiverText, selectorLine, pureStaticReceiver, target, na,
                    commutative, idempotent, line, effect);
        }
    }

    record CallJavaCtor(
            MirVal result,
            MirVal callee,
            String calleeText,
            boolean pureStaticCallee,
            LoweringTargets.JavaConstructorTarget target,
            List<MirVal> args,
            int line,
            MirEffect effect
    ) implements MirInst {
        public CallJavaCtor {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(target, "target");
            args = List.copyOf(args);
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            if (callee == null) return args;
            List<MirVal> out = new ArrayList<>(1 + args.size());
            out.add(callee);
            out.addAll(args);
            return out;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nc = callee != null ? fn.apply(callee) : null;
            List<MirVal> na = remapList(args, fn);
            return (nc == callee && na == args) ? this
                    : new CallJavaCtor(result, nc, calleeText, pureStaticCallee, target, na, line, effect);
        }
    }

    record RecordNew(
            MirVal result,
            int classId,
            String classTypeId,
            String classJvmName,
            MirVal classRef,
            boolean classRefIsInstance,
            PrimitiveKind[] paramKinds,
            List<MirVal> args,
            LoweringTargets.ArgumentBinding binding,
            int line,
            MirEffect effect
    ) implements MirInst {
        public RecordNew {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(classRef, "classRef");
            paramKinds = paramKinds != null ? paramKinds.clone() : new PrimitiveKind[0];
            args = List.copyOf(args);
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            List<MirVal> out = new ArrayList<>(1 + args.size());
            out.add(classRef);
            out.addAll(args);
            return out;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal ncr = fn.apply(classRef);
            List<MirVal> na = remapList(args, fn);
            return (ncr == classRef && na == args) ? this
                    : new RecordNew(result, classId, classTypeId, classJvmName, ncr, classRefIsInstance,
                    paramKinds, na, binding, line, effect);
        }
    }

    record RecordReuse(
            MirVal result,
            MirVal target,
            int classId,
            String classTypeId,
            String classJvmName,
            PrimitiveKind[] paramKinds,
            List<MirVal> args,
            int line,
            MirEffect effect
    ) implements MirInst {
        public RecordReuse {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(target, "target");
            paramKinds = paramKinds != null ? paramKinds.clone() : new PrimitiveKind[0];
            args = List.copyOf(args);
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            List<MirVal> out = new ArrayList<>(1 + args.size());
            out.add(target);
            out.addAll(args);
            return out;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nt = fn.apply(target);
            List<MirVal> na = remapList(args, fn);
            return (nt == target && na == args) ? this
                    : new RecordReuse(result, nt, classId, classTypeId, classJvmName, paramKinds, na, line, effect);
        }
    }

    record Move(MirVal result, HirVar var, MirVal operand, int line) implements MirInst {
        public Move {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this : new Move(result, var, no, line);
        }
    }

    record Freeze(MirVal result, MirVal operand, int line) implements MirInst {
        public Freeze {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(operand, "operand");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(operand);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal no = fn.apply(operand);
            return no == operand ? this : new Freeze(result, no, line);
        }
    }

    // ---------------------------------------------------------------- pinned slots, scopes, for-iters, try helpers

    /** Declares/initializes a block-local pinned variable (allocates fresh {@code Cell} or undefined flag on each entry). */
    record SlotDecl(HirVar var, int line) implements MirInst {
        public SlotDecl {
            Objects.requireNonNull(var, "var");
        }

        @Override
        public MirVal result() {
            return null;
        }

        @Override
        public MirEffect effect() {
            return MirEffect.WRITE_SHARED;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of();
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            return this;
        }
    }

    /** Read from a pinned variable (closure {@code Cell}, {@code mayBeUndefined} local, or {@code try}-mutated local). */
    record SlotRead(MirVal result, HirVar var, int line, MirEffect effect) implements MirInst {
        public SlotRead {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(var, "var");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of();
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            return this;
        }
    }

    /** Write to a pinned variable (closure {@code Cell}, {@code mayBeUndefined} local, or {@code try}-mutated local). */
    record SlotWrite(HirVar var, SlotOpKind opKind, MirVal value, int line, MirEffect effect) implements MirInst {
        public SlotWrite {
            Objects.requireNonNull(var, "var");
            Objects.requireNonNull(opKind, "opKind");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public MirVal result() {
            return null;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(value);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nv = fn.apply(value);
            return nv == value ? this : new SlotWrite(var, opKind, nv, line, effect);
        }
    }

    /** Pushes a child {@code Namespace} for an {@code opensNamespaceScope} block, returning the previous namespace. */
    record ScopeEnter(MirVal result, String[] declaredNames, int line) implements MirInst {
        public ScopeEnter {
            Objects.requireNonNull(result, "result");
            declaredNames = declaredNames != null ? declaredNames.clone() : new String[0];
        }

        @Override
        public MirEffect effect() {
            return MirEffect.IMPURE_THROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of();
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            return this;
        }
    }

    /** Restores the previous {@code Namespace} captured by {@link ScopeEnter}. */
    record ScopeExit(MirVal prevNamespace, int line) implements MirInst {
        public ScopeExit {
            Objects.requireNonNull(prevNamespace, "prevNamespace");
        }

        @Override
        public MirVal result() {
            return null;
        }

        @Override
        public MirEffect effect() {
            return MirEffect.WRITE_SHARED;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(prevNamespace);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal np = fn.apply(prevNamespace);
            return np == prevNamespace ? this : new ScopeExit(np, line);
        }
    }

    /** Initializes iterator/cursor state for a {@code for} loop ({@code BIP013}). */
    record ForIterInit(
            MirVal result,
            MirVal iterable,
            LoweringTargets.ForLoopTarget target,
            int line,
            MirEffect effect
    ) implements MirInst {
        public ForIterInit {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(iterable, "iterable");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(iterable);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal ni = fn.apply(iterable);
            return ni == iterable ? this : new ForIterInit(result, ni, target, line, effect);
        }
    }

    /** Checks whether a {@code for} iterator has another element (returns {@code bool}). */
    record ForIterHasNext(
            MirVal result,
            MirVal iterator,
            MirVal index,
            MirVal length,
            LoweringTargets.ForLoopTarget target,
            int line,
            MirEffect effect
    ) implements MirInst {
        public ForIterHasNext {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(iterator, "iterator");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            List<MirVal> out = new ArrayList<>(3);
            out.add(iterator);
            if (index != null) out.add(index);
            if (length != null) out.add(length);
            return out;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nit = fn.apply(iterator);
            MirVal nix = index != null ? fn.apply(index) : null;
            MirVal nln = length != null ? fn.apply(length) : null;
            return (nit == iterator && nix == index && nln == length) ? this
                    : new ForIterHasNext(result, nit, nix, nln, target, line, effect);
        }
    }

    /** Advances a {@code for} iterator and produces the loop element. */
    record ForIterNext(
            MirVal result,
            MirVal iterator,
            MirVal index,
            LoweringTargets.ForLoopTarget target,
            LoweringTargets.JavaSamTarget elemSamTarget,
            int line,
            MirEffect effect
    ) implements MirInst {
        public ForIterNext {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(iterator, "iterator");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return index != null ? List.of(iterator, index) : List.of(iterator);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nit = fn.apply(iterator);
            MirVal nix = index != null ? fn.apply(index) : null;
            return (nit == iterator && nix == index) ? this
                    : new ForIterNext(result, nit, nix, target, elemSamTarget, line, effect);
        }
    }

    /** Calls {@code ThrownException.value()} inside a {@code try}/{@code catch} dispatcher ({@code BIP028}). */
    record UnwrapThrown(MirVal result, MirVal thrownExn, int line) implements MirInst {
        public UnwrapThrown {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(thrownExn, "thrownExn");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(thrownExn);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nt = fn.apply(thrownExn);
            return nt == thrownExn ? this : new UnwrapThrown(result, nt, line);
        }
    }

    /** Checks whether an unwrapped Babble thrown value matches a {@code HirCatch} target ({@code BIP028}). */
    record CatchMatch(
            MirVal result,
            MirVal value,
            LoweringTargets.CatchTarget target,
            int line
    ) implements MirInst {
        public CatchMatch {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(target, "target");
        }

        @Override
        public MirEffect effect() {
            return MirEffect.PURE_NOTHROW;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(value);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal nv = fn.apply(value);
            return nv == value ? this : new CatchMatch(result, nv, target, line);
        }
    }

    /** Calls {@code Throwables.addSuppressed(primary, secondary)} when a {@code finally} throws ({@code BIP028-D6}). */
    record AddSuppressed(MirVal primary, MirVal secondary, int line) implements MirInst {
        public AddSuppressed {
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(secondary, "secondary");
        }

        @Override
        public MirVal result() {
            return null;
        }

        @Override
        public MirEffect effect() {
            return MirEffect.WRITE_SHARED;
        }

        @Override
        public List<MirVal> usedValues() {
            return List.of(primary, secondary);
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            MirVal np = fn.apply(primary);
            MirVal ns = fn.apply(secondary);
            return (np == primary && ns == secondary) ? this : new AddSuppressed(np, ns, line);
        }
    }

    /**
     * Atomic Babble-specific expression whose sub-expressions have already been lowered to SSA
     * {@link #operands()} (e.g., {@code DynamicCall}, {@code SuperCall}, {@code OverloadDispatch},
     * {@code CoreCollectionCall}, {@code ArrayCreate}, {@code ClassExpr}, {@code FunctionRef},
     * {@code NameRead}/{@code NameAssign}, {@code ModuleExportGet}).
     */
    record OpaqueExpr(
            MirVal result,
            HirExpr template,
            List<MirVal> operands,
            int line,
            MirEffect effect
    ) implements MirInst {
        public OpaqueExpr {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(template, "template");
            operands = List.copyOf(operands);
            Objects.requireNonNull(effect, "effect");
        }

        @Override
        public List<MirVal> usedValues() {
            return operands;
        }

        @Override
        public MirInst remapUses(UnaryOperator<MirVal> fn) {
            List<MirVal> no = remapList(operands, fn);
            return no == operands ? this : new OpaqueExpr(result, template, no, line, effect);
        }
    }
}
