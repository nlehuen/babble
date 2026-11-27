package org.babblelang.ir.hir;

import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.runtime.CallSite;

/**
 * Sealed hierarchy of High-Level Typed IR ({@code HIR}) expressions (BIP014-I1).
 *
 * <p>Every node is an immutable Java 21 {@code record} detached from ANTLR4 {@code
 * ParserRuleContext}, carrying its resolved {@link ExprMeta} so Nanopass rewriters and {@code
 * HirEmitter} can pattern-match exhaustively without consulting parse-tree side tables.
 */
public sealed interface HirExpr
        permits HirExpr.Const,
                HirExpr.IntParseFallback,
                HirExpr.CoreCollectionRef,
                HirExpr.CoreExceptionRef,
                HirExpr.VarRead,
                HirExpr.NameRead,
                HirExpr.ThisRef,
                HirExpr.RecurseRef,
                HirExpr.Def,
                HirExpr.VarAssign,
                HirExpr.NameAssign,
                HirExpr.ThisFieldInit,
                HirExpr.ThisFieldAssign,
                HirExpr.JavaFieldAssign,
                HirExpr.DynamicFieldAssign,
                HirExpr.ThisFieldGet,
                HirExpr.JavaFieldGet,
                HirExpr.ArrayCreate,
                HirExpr.ArrayOp,
                HirExpr.ModuleExportGet,
                HirExpr.DynamicSelect,
                HirExpr.Unary,
                HirExpr.Binary,
                HirExpr.Range,
                HirExpr.Logic,
                HirExpr.Is,
                HirExpr.As,
                HirExpr.NonNull,
                HirExpr.SpreadAdapt,
                HirExpr.JavaSamAdapt,
                HirExpr.Block,
                HirExpr.If,
                HirExpr.Match,
                HirExpr.While,
                HirExpr.For,
                HirExpr.Try,
                HirExpr.Break,
                HirExpr.Continue,
                HirExpr.Return,
                HirExpr.Throw,
                HirExpr.CoreCollectionCall,
                HirExpr.SuperCall,
                HirExpr.SelfRecurseCall,
                HirExpr.SelfTailCall,
                HirExpr.DirectBabbleCall,
                HirExpr.DirectThisMethodCall,
                HirExpr.InterfaceCall,
                HirExpr.JavaConstructorCall,
                HirExpr.JavaMethodCall,
                HirExpr.CoreMethodCall,
                HirExpr.SafeCall,
                HirExpr.DynamicCall,
                HirExpr.MakeOverloadSet,
                HirExpr.OverloadSelect,
                HirExpr.OverloadDispatch,
                HirExpr.FunctionRef,
                HirExpr.ClassExpr,
                HirExpr.InterfaceExpr,
                HirExpr.TypeApply,
                HirExpr.RecordNew,
                HirExpr.RecordReuse,
                HirExpr.Move,
                HirExpr.Freeze,
                HirExpr.Narrow,
                HirExpr.Convert,
                HirExpr.Widen {

    ExprMeta meta();

    enum UnaryOp {
        NEGATE,
        NOT
    }

    enum BinaryOp {
        ADD("+"),
        SUB("-"),
        MUL("*"),
        DIV("/"),
        FLOOR_DIV("//"),
        MOD("mod"),
        LT("<"),
        LTE("<="),
        EQ("=="),
        NEQ("<>"),
        GTE(">="),
        GT(">");

        private final String symbol;

        BinaryOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public boolean isComparison() {
            return switch (this) {
                case LT, LTE, EQ, NEQ, GTE, GT -> true;
                default -> false;
            };
        }

        public boolean isArithmetic() {
            return !isComparison();
        }
    }

    enum LogicOp {
        AND,
        OR
    }

    // ---------------------------------------------------------------- literals

    /**
     * Compile-time constant: {@code null}, {@code Boolean}, {@code Byte}, {@code Short}, {@code
     * Integer}, {@code Long}, {@code Float}, {@code Double}, {@code Character}, or {@code String}.
     */
    record Const(Object value, ExprMeta meta) implements HirExpr {}

    /**
     * Out-of-range integer literal kept to throw the exact runtime {@code NumberFormatException}.
     */
    record IntParseFallback(String text, ExprMeta meta) implements HirExpr {}

    /**
     * Bare core collection constructor reference ({@code list}, {@code set}, {@code map}, etc.).
     */
    record CoreCollectionRef(String collectionKind, ExprMeta meta) implements HirExpr {}

    /**
     * Core {@code exception} class reference ({@code BabbleException}) as a value ({@code
     * BIP028-D2}).
     */
    record CoreExceptionRef(ExprMeta meta) implements HirExpr {}

    // ---------------------------------------------------------------- variables & names

    record VarRead(HirVar var, ExprMeta meta) implements HirExpr {}

    record NameRead(String name, boolean finalBinding, boolean definitelyDefined, ExprMeta meta)
            implements HirExpr {
        public NameRead(String name, ExprMeta meta) {
            this(name, false, false, meta);
        }

        public NameRead asDefinitelyDefined() {
            return definitelyDefined ? this : new NameRead(name, finalBinding, true, meta);
        }
    }

    record ThisRef(HirVar var, int enclosingClassId, ExprMeta meta) implements HirExpr {}

    record RecurseRef(HirVar var, ExprMeta meta) implements HirExpr {}

    // ---------------------------------------------------------------- definitions & assignments

    record Def(
            String name, LoweringTargets.DefTarget target, HirVar var, HirExpr value, ExprMeta meta)
            implements HirExpr {}

    record VarAssign(
            HirVar var,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta)
            implements HirExpr {}

    record NameAssign(
            String name,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta)
            implements HirExpr {}

    /**
     * One-time initialization of a declared field of {@code this} inside an {@code init} block
     * ({@code BIP040-D10}). Emitted as {@code PUTFIELD} inside JVM {@code <init>} (legal on {@code
     * ACC_FINAL} fields) or local staging in Wasm 3.0.
     */
    record ThisFieldInit(
            int classId, int memberIndex, String memberName, HirExpr value, ExprMeta meta)
            implements HirExpr {}

    record ThisFieldAssign(
            HirExpr receiver,
            int classId,
            int memberIndex,
            String memberName,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta)
            implements HirExpr {}

    record JavaFieldAssign(
            HirExpr receiver,
            String receiverText,
            boolean pureStaticReceiver,
            LoweringTargets.JavaFieldTarget target,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta)
            implements HirExpr {}

    record DynamicFieldAssign(
            HirExpr receiver,
            String receiverText,
            String memberName,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta)
            implements HirExpr {}

    // ---------------------------------------------------------------- member selection

    record ThisFieldGet(
            HirExpr receiver,
            String receiverText,
            int classId,
            String externalClassJvmName,
            int memberIndex,
            String memberName,
            PrimitiveKind fieldKind,
            boolean immutableField,
            boolean definitelyDefined,
            ExprMeta meta)
            implements HirExpr {
        public ThisFieldGet {
            fieldKind = fieldKind != null ? fieldKind : PrimitiveKind.NONE;
        }

        public ThisFieldGet(
                HirExpr receiver, int classId, int memberIndex, String memberName, ExprMeta meta) {
            this(
                    receiver,
                    null,
                    classId,
                    null,
                    memberIndex,
                    memberName,
                    PrimitiveKind.NONE,
                    false,
                    false,
                    meta);
        }
    }

    record JavaFieldGet(
            HirExpr receiver,
            String receiverText,
            boolean pureStaticReceiver,
            LoweringTargets.JavaFieldTarget target,
            ExprMeta meta)
            implements HirExpr {}

    record ArrayCreate(
            LoweringTargets.ArrayCreationKind kind,
            PrimitiveKind elemKind,
            HirArrayElementType elementType,
            String componentInternalName,
            boolean componentIsJavaInterface,
            Class<?> componentClass,
            List<HirExpr> args,
            ExprMeta meta)
            implements HirExpr {
        public ArrayCreate {
            elementType =
                    elementType != null
                            ? elementType
                            : HirArrayElementType.from(elemKind, null, false, 0, componentClass);
            args = List.copyOf(args);
        }

        public ArrayCreate(
                LoweringTargets.ArrayCreationKind kind,
                PrimitiveKind elemKind,
                String componentInternalName,
                boolean componentIsJavaInterface,
                Class<?> componentClass,
                List<HirExpr> args,
                ExprMeta meta) {
            this(
                    kind,
                    elemKind,
                    HirArrayElementType.from(elemKind, null, false, 0, componentClass),
                    componentInternalName,
                    componentIsJavaInterface,
                    componentClass,
                    args,
                    meta);
        }
    }

    record ArrayOp(
            LoweringTargets.ArrayOpKind kind,
            HirExpr receiver,
            String receiverText,
            PrimitiveKind elemKind,
            HirArrayElementType elementType,
            Class<?> arrayClass,
            Class<?> componentClass,
            List<HirExpr> args,
            ExprMeta meta)
            implements HirExpr {
        public ArrayOp {
            elementType =
                    elementType != null
                            ? elementType
                            : HirArrayElementType.from(elemKind, null, false, 0, componentClass);
            args = List.copyOf(args);
        }

        public ArrayOp(
                LoweringTargets.ArrayOpKind kind,
                HirExpr receiver,
                String receiverText,
                PrimitiveKind elemKind,
                Class<?> arrayClass,
                Class<?> componentClass,
                List<HirExpr> args,
                ExprMeta meta) {
            this(
                    kind,
                    receiver,
                    receiverText,
                    elemKind,
                    HirArrayElementType.from(elemKind, null, false, 0, componentClass),
                    arrayClass,
                    componentClass,
                    args,
                    meta);
        }
    }

    record ModuleExportGet(
            HirExpr receiver, LoweringTargets.ModuleExportTarget target, ExprMeta meta)
            implements HirExpr {}

    record DynamicSelect(
            HirExpr receiver, String receiverText, String memberName, boolean safe, ExprMeta meta)
            implements HirExpr {}

    // ---------------------------------------------------------------- operators

    record Unary(UnaryOp op, HirExpr operand, String operandText, ExprMeta meta)
            implements HirExpr {}

    record Binary(
            BinaryOp op,
            HirExpr left,
            HirExpr right,
            String leftText,
            String rightText,
            ExprMeta meta)
            implements HirExpr {}

    record Logic(LogicOp op, HirExpr left, HirExpr right, boolean rightNarrowed, ExprMeta meta)
            implements HirExpr {
        public Logic(LogicOp op, HirExpr left, HirExpr right, ExprMeta meta) {
            this(op, left, right, false, meta);
        }
    }

    // ---------------------------------------------------------------- type operations

    record Is(
            HirExpr left,
            String builtin,
            LoweringTargets.IsTarget target,
            List<LoweringTargets.IsTarget> intersectionTargets,
            String descriptor,
            ExprMeta meta)
            implements HirExpr {
        public Is {
            intersectionTargets =
                    intersectionTargets == null ? List.of() : List.copyOf(intersectionTargets);
        }

        public Is(
                HirExpr left,
                String builtin,
                LoweringTargets.IsTarget target,
                String descriptor,
                ExprMeta meta) {
            this(left, builtin, target, List.of(), descriptor, meta);
        }
    }

    record As(
            HirExpr left,
            LoweringTargets.IsTarget target,
            LoweringTargets.JavaSamTarget samTarget,
            String targetDescriptor,
            ExprMeta meta)
            implements HirExpr {
        public As(
                HirExpr left,
                LoweringTargets.IsTarget target,
                String targetDescriptor,
                ExprMeta meta) {
            this(left, target, null, targetDescriptor, meta);
        }
    }

    record NonNull(HirExpr operand, String expectedType, ExprMeta meta) implements HirExpr {}

    record SpreadAdapt(HirExpr value, LoweringTargets.SpreadAdapt adapt, ExprMeta meta)
            implements HirExpr {}

    /** Java-interop SAM / SAM+markers adapter conversion ({@code BIP022-D7}). */
    record JavaSamAdapt(HirExpr value, LoweringTargets.JavaSamTarget target, ExprMeta meta)
            implements HirExpr {}

    // ---------------------------------------------------------------- control flow

    record Block(
            List<HirExpr> statements,
            boolean opensNamespaceScope,
            String[] declaredNames,
            List<HirVar> declaredVars,
            int innerWideningRank,
            ExprMeta meta)
            implements HirExpr {
        public Block {
            statements = List.copyOf(statements);
            declaredNames = declaredNames != null ? declaredNames.clone() : new String[0];
            declaredVars = List.copyOf(declaredVars);
        }

        public boolean isTransparentSingleExpr() {
            return statements.size() == 1
                    && !opensNamespaceScope
                    && declaredVars.isEmpty()
                    && innerWideningRank < 0
                    && meta.wideningRank() < 0;
        }
    }

    record If(HirExpr cond, HirExpr thenBranch, HirExpr elseBranch, ExprMeta meta)
            implements HirExpr {}

    record MatchArm(
            HirPattern pattern,
            List<HirVar> boundVars,
            HirExpr guard,
            HirExpr body,
            int bodyWideningRank,
            int line) {
        public MatchArm {
            boundVars = List.copyOf(boundVars);
        }
    }

    record MatchElse(HirVar var, HirExpr body, int bodyWideningRank, int line) {}

    record Match(
            HirExpr scrutinee,
            HirVar scrutineeVar,
            List<MatchArm> arms,
            MatchElse elseArm,
            boolean exhaustive,
            ExprMeta meta)
            implements HirExpr {
        public Match {
            arms = List.copyOf(arms);
        }
    }

    record While(HirExpr cond, HirExpr body, ExprMeta meta) implements HirExpr {}

    record Range(
            HirExpr start,
            HirExpr end,
            boolean inclusive,
            PrimitiveKind elemKind,
            boolean stringElement,
            ExprMeta meta)
            implements HirExpr {
        public Range {
            elemKind = elemKind != null ? elemKind : PrimitiveKind.NONE;
        }
    }

    record CountedRangeLoop(
            HirExpr start,
            HirExpr end,
            HirExpr step,
            boolean inclusive,
            boolean descending,
            PrimitiveKind elemKind) {
        public CountedRangeLoop {
            elemKind = elemKind != null ? elemKind : PrimitiveKind.INT;
        }
    }

    record For(
            HirExpr iterable,
            LoweringTargets.ForLoopTarget target,
            HirVar loopVar,
            List<HirVar> bodyDeclaredVars,
            List<HirExpr> body,
            int bodyWideningRank,
            LoweringTargets.JavaSamTarget elemSamTarget,
            CountedRangeLoop countedRange,
            ExprMeta meta)
            implements HirExpr {
        public For {
            bodyDeclaredVars = List.copyOf(bodyDeclaredVars);
            body = List.copyOf(body);
        }

        public For(
                HirExpr iterable,
                LoweringTargets.ForLoopTarget target,
                HirVar loopVar,
                List<HirVar> bodyDeclaredVars,
                List<HirExpr> body,
                int bodyWideningRank,
                LoweringTargets.JavaSamTarget elemSamTarget,
                ExprMeta meta) {
            this(
                    iterable,
                    target,
                    loopVar,
                    bodyDeclaredVars,
                    body,
                    bodyWideningRank,
                    elemSamTarget,
                    null,
                    meta);
        }
    }

    record HirCatch(
            HirVar var,
            LoweringTargets.CatchTarget target,
            List<HirExpr> body,
            List<HirVar> declaredVars,
            boolean opensNamespaceScope,
            String[] declaredNames,
            int wideningRank,
            int line) {
        public HirCatch {
            body = List.copyOf(body);
            declaredVars = List.copyOf(declaredVars);
            declaredNames = declaredNames != null ? declaredNames.clone() : new String[0];
        }
    }

    record Try(
            List<HirExpr> tryBody,
            List<HirVar> tryDeclaredVars,
            boolean tryOpensNamespaceScope,
            String[] tryDeclaredNames,
            int tryWideningRank,
            List<HirCatch> catches,
            List<HirExpr> finallyBody,
            List<HirVar> finallyDeclaredVars,
            boolean finallyOpensNamespaceScope,
            String[] finallyDeclaredNames,
            boolean finallyNothrow,
            boolean hasFinally,
            ExprMeta meta)
            implements HirExpr {
        public Try {
            tryBody = List.copyOf(tryBody);
            tryDeclaredVars = List.copyOf(tryDeclaredVars);
            tryDeclaredNames = tryDeclaredNames != null ? tryDeclaredNames.clone() : new String[0];
            catches = List.copyOf(catches);
            finallyBody = List.copyOf(finallyBody);
            finallyDeclaredVars = List.copyOf(finallyDeclaredVars);
            finallyDeclaredNames =
                    finallyDeclaredNames != null ? finallyDeclaredNames.clone() : new String[0];
        }
    }

    record Break(HirExpr value, ExprMeta meta) implements HirExpr {}

    record Continue(ExprMeta meta) implements HirExpr {}

    record Return(HirExpr value, ExprMeta meta) implements HirExpr {}

    record Throw(HirExpr value, boolean isJavaThrowable, ExprMeta meta) implements HirExpr {}

    // ---------------------------------------------------------------- calls

    record CoreCollectionCall(String collectionKind, List<HirCallArg> args, ExprMeta meta)
            implements HirExpr {
        public CoreCollectionCall {
            args = List.copyOf(args);
        }
    }

    record SuperCall(
            HirVar thisVar,
            int ownerClassId,
            String memberName,
            LoweringTargets.SuperTargetInfo targetInfo,
            HirTypeRef superTypeRef,
            List<HirCallArg> args,
            LoweringTargets.ArgumentBinding binding,
            CallSite callSite,
            ExprMeta meta)
            implements HirExpr {
        public SuperCall {
            args = List.copyOf(args);
        }
    }

    record SelfRecurseCall(
            int functionId,
            List<HirExpr> args,
            LoweringTargets.ArgumentBinding binding,
            ExprMeta meta)
            implements HirExpr {
        public SelfRecurseCall {
            args = List.copyOf(args);
        }
    }

    /**
     * A self tail call (docs/design/bip024_tail-calls.md) : a call of function {@code functionId}
     * to itself whose value is the function's result, run as a jump back to the start of the
     * function with new arguments. It only ever appears in the body of that function.
     */
    record SelfTailCall(
            int functionId,
            List<HirCallArg> args,
            LoweringTargets.ArgumentBinding binding,
            ExprMeta meta)
            implements HirExpr {
        public SelfTailCall {
            args = List.copyOf(args);
        }
    }

    record DirectBabbleCall(
            HirExpr callee,
            int targetFunctionId,
            LoweringTargets.ExportedFunctionTarget externalTarget,
            List<HirExpr> args,
            LoweringTargets.ArgumentBinding binding,
            ExprMeta meta)
            implements HirExpr {
        public DirectBabbleCall {
            args = List.copyOf(args);
        }

        public DirectBabbleCall(
                HirExpr callee,
                int targetFunctionId,
                List<HirExpr> args,
                LoweringTargets.ArgumentBinding binding,
                ExprMeta meta) {
            this(callee, targetFunctionId, null, args, binding, meta);
        }
    }

    record DirectThisMethodCall(
            HirExpr receiver,
            String receiverText,
            int classId,
            String externalClassJvmName,
            String classTypeId,
            String memberName,
            int overloadAltIndex,
            int targetFunctionId,
            LoweringTargets.FunctionSignature externalSignature,
            List<HirCallArg> args,
            LoweringTargets.ArgumentBinding binding,
            CallSite callSite,
            ExprMeta meta)
            implements HirExpr {
        public DirectThisMethodCall {
            args = List.copyOf(args);
        }

        public DirectThisMethodCall(
                HirExpr receiver,
                String receiverText,
                int classId,
                String memberName,
                int overloadAltIndex,
                int targetFunctionId,
                List<HirCallArg> args,
                LoweringTargets.ArgumentBinding binding,
                CallSite callSite,
                ExprMeta meta) {
            this(
                    receiver,
                    receiverText,
                    classId,
                    null,
                    null,
                    memberName,
                    overloadAltIndex,
                    targetFunctionId,
                    null,
                    args,
                    binding,
                    callSite,
                    meta);
        }

        public DirectThisMethodCall(
                HirExpr receiver,
                int classId,
                String memberName,
                int targetFunctionId,
                List<HirCallArg> args,
                LoweringTargets.ArgumentBinding binding,
                CallSite callSite,
                ExprMeta meta) {
            this(
                    receiver,
                    null,
                    classId,
                    null,
                    null,
                    memberName,
                    -1,
                    targetFunctionId,
                    null,
                    args,
                    binding,
                    callSite,
                    meta);
        }
    }

    record InterfaceCall(
            HirExpr receiver,
            String receiverText,
            int selectorLine,
            String interfaceTypeId,
            String memberName,
            LoweringTargets.FunctionSignature signature,
            List<HirExpr> args,
            LoweringTargets.ArgumentBinding binding,
            ExprMeta meta)
            implements HirExpr {
        public InterfaceCall {
            args = List.copyOf(args);
        }
    }

    record JavaConstructorCall(
            HirExpr callee,
            String calleeText,
            boolean pureStaticCallee,
            LoweringTargets.JavaConstructorTarget target,
            List<HirExpr> args,
            ExprMeta meta)
            implements HirExpr {
        public JavaConstructorCall {
            args = List.copyOf(args);
        }
    }

    record JavaMethodCall(
            HirExpr receiver,
            String receiverText,
            int selectorLine,
            boolean pureStaticReceiver,
            LoweringTargets.JavaCallTarget target,
            List<HirExpr> args,
            ExprMeta meta)
            implements HirExpr {
        public JavaMethodCall {
            args = List.copyOf(args);
        }
    }

    /**
     * Target-neutral portable core method call on {@code string}, core collections ({@code
     * list}..{@code entry}), {@code exception}, or portable {@code Appendable} ({@code BIP025-D3},
     * {@code BIP025-D6}, {@code BIP025-D12}).
     */
    record CoreMethodCall(
            HirExpr receiver,
            String receiverText,
            int selectorLine,
            String coreKind,
            String memberName,
            List<HirExpr> args,
            ExprMeta meta)
            implements HirExpr {
        public CoreMethodCall {
            args = List.copyOf(args);
        }
    }

    record SafeCall(
            HirExpr receiver,
            String receiverText,
            String memberName,
            int selectorLine,
            String selectorCheckedType,
            List<HirCallArg> args,
            LoweringTargets.ArgumentBinding binding,
            LoweringTargets.FunctionSignature babbleSig,
            CallSite callSite,
            ExprMeta meta)
            implements HirExpr {
        public SafeCall {
            args = List.copyOf(args);
        }
    }

    record DynamicCall(
            HirExpr callee,
            List<HirCallArg> args,
            LoweringTargets.ArgumentBinding binding,
            LoweringTargets.FunctionSignature babbleSig,
            CallSite callSite,
            int constructorIndex,
            ExprMeta meta)
            implements HirExpr {
        public DynamicCall {
            args = List.copyOf(args);
        }

        public DynamicCall(
                HirExpr callee,
                List<HirCallArg> args,
                LoweringTargets.ArgumentBinding binding,
                LoweringTargets.FunctionSignature babbleSig,
                CallSite callSite,
                ExprMeta meta) {
            this(callee, args, binding, babbleSig, callSite, -1, meta);
        }
    }

    // ---------------------------------------------------------------- overload sets (BIP021-I5)

    record MakeOverloadSet(
            List<HirExpr> operands, List<LoweringTargets.OverloadAltMetaInfo> metas, ExprMeta meta)
            implements HirExpr {
        public MakeOverloadSet {
            operands = List.copyOf(operands);
            metas = List.copyOf(metas);
        }
    }

    record OverloadSelect(
            HirExpr set, int index, String signatureKey, boolean exactSet, ExprMeta meta)
            implements HirExpr {}

    record OverloadBranch(
            List<LoweringTargets.OverloadSplitCheck> checks,
            int directFunctionId,
            int altIndex,
            String signatureKey,
            boolean exactSet,
            LoweringTargets.FunctionSignature signature,
            LoweringTargets.ArgumentBinding binding,
            PrimitiveKind resultKind) {
        public OverloadBranch {
            checks = List.copyOf(checks);
        }
    }

    record OverloadDispatch(
            HirExpr callee,
            String calleeText,
            CallSite callSite,
            List<HirExpr> args,
            List<OverloadBranch> branches,
            ExprMeta meta)
            implements HirExpr {
        public OverloadDispatch {
            args = List.copyOf(args);
            branches = List.copyOf(branches);
        }
    }

    // ---------------------------------------------------------------- value constructors

    record FunctionRef(int functionId, ExprMeta meta) implements HirExpr {}

    record ClassExpr(int classId, ExprMeta meta) implements HirExpr {}

    record InterfaceExpr(int interfaceId, ExprMeta meta) implements HirExpr {}

    record TypeApply(HirExpr inner, ExprMeta meta) implements HirExpr {}

    // ---------------------------------------------------------------- record & ownership ops
    // (BIP014-I4)

    /**
     * Direct fast constructor call for a capture-free method-only compiled {@code record} ({@code
     * BIP014-I4}).
     */
    record RecordNew(
            int classId,
            String classTypeId,
            String classJvmName,
            HirExpr classRef,
            boolean classRefIsInstance,
            PrimitiveKind[] paramKinds,
            List<HirExpr> args,
            LoweringTargets.ArgumentBinding binding,
            ExprMeta meta)
            implements HirExpr {
        public RecordNew {
            paramKinds = paramKinds != null ? paramKinds.clone() : new PrimitiveKind[0];
            args = List.copyOf(args);
        }

        public RecordNew(
                int classId,
                String classTypeId,
                String classJvmName,
                HirExpr classRef,
                PrimitiveKind[] paramKinds,
                List<HirExpr> args,
                LoweringTargets.ArgumentBinding binding,
                ExprMeta meta) {
            this(
                    classId,
                    classTypeId,
                    classJvmName,
                    classRef,
                    false,
                    paramKinds,
                    args,
                    binding,
                    meta);
        }
    }

    /**
     * Functional-but-in-place ({@code O10}) update of a uniquely owned {@code record} instance
     * ({@code BIP014-I4}).
     */
    record RecordReuse(
            HirExpr target,
            int classId,
            String classTypeId,
            String classJvmName,
            PrimitiveKind[] paramKinds,
            List<HirExpr> args,
            ExprMeta meta)
            implements HirExpr {
        public RecordReuse {
            paramKinds = paramKinds != null ? paramKinds.clone() : new PrimitiveKind[0];
            args = List.copyOf(args);
        }
    }

    /**
     * Ownership transfer marker ({@code move(x)}), zero-cost at runtime ({@code BIP007-I2}, {@code
     * BIP014-I4}).
     */
    record Move(HirVar var, HirExpr operand, ExprMeta meta) implements HirExpr {}

    /**
     * Deep-immutability marker ({@code freeze(expr)}), zero-cost at runtime ({@code BIP007-I2},
     * {@code BIP014-I4}).
     */
    record Freeze(HirExpr operand, ExprMeta meta) implements HirExpr {}

    // ---------------------------------------------------------------- guard anchors & explicit
    // conversions (BIP039-D3, BIP039-D4)

    /** Kind of runtime invariant established by an enclosing guard ({@code BIP039-D3}). */
    enum NarrowKind {
        NON_NULL,
        IS_TYPE
    }

    /**
     * Zero-cost guard dependency anchor ({@code BIP039-D3}).
     *
     * <p>Wraps a narrowed read inside an {@code if}, {@code &&}/{@code ||}, {@code while}, or
     * {@code match} guard scope. Both JVM and Wasm emitters emit {@code operand} directly with zero
     * runtime instructions, while {@code guardId} structurally prevents LICM, AEGraph, and
     * predicate reordering from moving this read above its guarding condition.
     */
    record Narrow(
            HirExpr operand, NarrowKind narrowKind, int guardId, String targetDesc, ExprMeta meta)
            implements HirExpr {}

    /** Explicit boxing, unboxing, or primitive kind conversion ({@code BIP039-D4}). */
    record Convert(HirExpr operand, PrimitiveKind targetKind, ExprMeta meta) implements HirExpr {}

    /**
     * Explicit numeric widening to target rank {@code 1..5} (short, int, long, float, double)
     * ({@code BIP039-D4}).
     */
    record Widen(HirExpr operand, int wideningRank, ExprMeta meta) implements HirExpr {}
}
