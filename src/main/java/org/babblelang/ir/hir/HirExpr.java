package org.babblelang.ir.hir;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.runtime.CallSite;

import java.util.List;

/**
 * Sealed hierarchy of High-Level Typed IR ({@code HIR}) expressions (BIP014-P1).
 *
 * <p>Every node is an immutable Java 21 {@code record} detached from ANTLR4 {@code ParserRuleContext},
 * carrying its resolved {@link ExprMeta} so Nanopass rewriters and {@code HirEmitter} can pattern-match
 * exhaustively without consulting parse-tree side tables.
 */
public sealed interface HirExpr permits
        HirExpr.Const,
        HirExpr.IntParseFallback,
        HirExpr.CoreCollectionRef,
        HirExpr.VarRead,
        HirExpr.NameRead,
        HirExpr.ThisRef,
        HirExpr.RecurseRef,
        HirExpr.Def,
        HirExpr.VarAssign,
        HirExpr.NameAssign,
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
        HirExpr.Logic,
        HirExpr.Is,
        HirExpr.As,
        HirExpr.NonNull,
        HirExpr.SpreadAdapt,
        HirExpr.Block,
        HirExpr.If,
        HirExpr.While,
        HirExpr.For,
        HirExpr.Break,
        HirExpr.Continue,
        HirExpr.Return,
        HirExpr.CoreCollectionCall,
        HirExpr.SuperCall,
        HirExpr.SelfRecurseCall,
        HirExpr.DirectBabbleCall,
        HirExpr.DirectThisMethodCall,
        HirExpr.InterfaceCall,
        HirExpr.JavaConstructorCall,
        HirExpr.JavaMethodCall,
        HirExpr.SafeCall,
        HirExpr.DynamicCall,
        HirExpr.MakeOverloadSet,
        HirExpr.OverloadSelect,
        HirExpr.OverloadDispatch,
        HirExpr.FunctionRef,
        HirExpr.ClassExpr,
        HirExpr.InterfaceExpr,
        HirExpr.TypeApply {

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

    /** Compile-time constant: {@code null}, {@code Boolean}, {@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@code Float}, {@code Double}, {@code Character}, or {@code String}. */
    record Const(Object value, ExprMeta meta) implements HirExpr {
    }

    /** Out-of-range integer literal kept to throw the exact runtime {@code NumberFormatException}. */
    record IntParseFallback(String text, ExprMeta meta) implements HirExpr {
    }

    /** Bare core collection constructor reference ({@code list}, {@code set}, {@code map}, etc.). */
    record CoreCollectionRef(String collectionKind, ExprMeta meta) implements HirExpr {
    }

    // ---------------------------------------------------------------- variables & names

    record VarRead(HirVar var, ExprMeta meta) implements HirExpr {
    }

    record NameRead(String name, ExprMeta meta) implements HirExpr {
    }

    record ThisRef(HirVar var, int enclosingClassId, ExprMeta meta) implements HirExpr {
    }

    record RecurseRef(HirVar var, ExprMeta meta) implements HirExpr {
    }

    // ---------------------------------------------------------------- definitions & assignments

    record Def(
            String name,
            ScopeAnalysis.DefTarget target,
            HirVar var,
            HirExpr value,
            ExprMeta meta
    ) implements HirExpr {
    }

    record VarAssign(
            HirVar var,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta
    ) implements HirExpr {
    }

    record NameAssign(
            String name,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta
    ) implements HirExpr {
    }

    record ThisFieldAssign(
            HirExpr receiver,
            int classId,
            int memberIndex,
            String memberName,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta
    ) implements HirExpr {
    }

    record JavaFieldAssign(
            HirExpr receiver,
            String receiverText,
            boolean pureStaticReceiver,
            ScopeAnalysis.JavaFieldTarget target,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta
    ) implements HirExpr {
    }

    record DynamicFieldAssign(
            HirExpr receiver,
            String receiverText,
            String memberName,
            String operator,
            HirExpr value,
            String targetText,
            String valueText,
            ExprMeta meta
    ) implements HirExpr {
    }

    // ---------------------------------------------------------------- member selection

    record ThisFieldGet(
            HirExpr receiver,
            int classId,
            int memberIndex,
            String memberName,
            ExprMeta meta
    ) implements HirExpr {
    }

    record JavaFieldGet(
            HirExpr receiver,
            String receiverText,
            boolean pureStaticReceiver,
            ScopeAnalysis.JavaFieldTarget target,
            ExprMeta meta
    ) implements HirExpr {
    }

    record ArrayCreate(
            ScopeAnalysis.ArrayCreationKind kind,
            ScopeAnalysis.PrimitiveKind elemKind,
            String componentInternalName,
            boolean componentIsJavaInterface,
            Class<?> componentClass,
            List<HirExpr> args,
            ExprMeta meta
    ) implements HirExpr {
    }

    record ArrayOp(
            ScopeAnalysis.ArrayOpKind kind,
            HirExpr receiver,
            String receiverText,
            ScopeAnalysis.PrimitiveKind elemKind,
            Class<?> arrayClass,
            Class<?> componentClass,
            List<HirExpr> args,
            ExprMeta meta
    ) implements HirExpr {
    }

    record ModuleExportGet(
            HirExpr receiver,
            ScopeAnalysis.ModuleExportTarget target,
            ExprMeta meta
    ) implements HirExpr {
    }

    record DynamicSelect(
            HirExpr receiver,
            String receiverText,
            String memberName,
            boolean safe,
            ExprMeta meta
    ) implements HirExpr {
    }

    // ---------------------------------------------------------------- operators

    record Unary(
            UnaryOp op,
            HirExpr operand,
            String operandText,
            ExprMeta meta
    ) implements HirExpr {
    }

    record Binary(
            BinaryOp op,
            HirExpr left,
            HirExpr right,
            String leftText,
            String rightText,
            ExprMeta meta
    ) implements HirExpr {
    }

    record Logic(
            LogicOp op,
            HirExpr left,
            HirExpr right,
            ExprMeta meta
    ) implements HirExpr {
    }

    // ---------------------------------------------------------------- type operations

    record Is(
            HirExpr left,
            String builtin,
            ScopeAnalysis.IsTarget target,
            String descriptor,
            ExprMeta meta
    ) implements HirExpr {
    }

    record As(
            HirExpr left,
            ScopeAnalysis.IsTarget target,
            String targetDescriptor,
            ExprMeta meta
    ) implements HirExpr {
    }

    record NonNull(
            HirExpr operand,
            String expectedType,
            ExprMeta meta
    ) implements HirExpr {
    }

    record SpreadAdapt(
            HirExpr value,
            ScopeAnalysis.SpreadAdapt adapt,
            ExprMeta meta
    ) implements HirExpr {
    }

    // ---------------------------------------------------------------- control flow

    record Block(
            List<HirExpr> statements,
            boolean opensNamespaceScope,
            String[] declaredNames,
            List<HirVar> declaredVars,
            int innerWideningRank,
            ExprMeta meta
    ) implements HirExpr {
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

    record If(
            HirExpr cond,
            HirExpr thenBranch,
            HirExpr elseBranch,
            ExprMeta meta
    ) implements HirExpr {
    }

    record While(
            HirExpr cond,
            HirExpr body,
            ExprMeta meta
    ) implements HirExpr {
    }

    record For(
            HirExpr iterable,
            ScopeAnalysis.ForLoopTarget target,
            HirVar loopVar,
            List<HirVar> bodyDeclaredVars,
            List<HirExpr> body,
            int bodyWideningRank,
            ExprMeta meta
    ) implements HirExpr {
        public For {
            bodyDeclaredVars = List.copyOf(bodyDeclaredVars);
            body = List.copyOf(body);
        }
    }

    record Break(HirExpr value, ExprMeta meta) implements HirExpr {
    }

    record Continue(ExprMeta meta) implements HirExpr {
    }

    record Return(HirExpr value, ExprMeta meta) implements HirExpr {
    }

    // ---------------------------------------------------------------- calls

    record CoreCollectionCall(
            String collectionKind,
            List<HirCallArg> args,
            ExprMeta meta
    ) implements HirExpr {
        public CoreCollectionCall {
            args = List.copyOf(args);
        }
    }

    record SuperCall(
            HirVar thisVar,
            int ownerClassId,
            String memberName,
            ScopeAnalysis.SuperTargetInfo targetInfo,
            HirTypeRef superTypeRef,
            List<HirCallArg> args,
            ScopeAnalysis.ArgumentBinding binding,
            CallSite callSite,
            ExprMeta meta
    ) implements HirExpr {
        public SuperCall {
            args = List.copyOf(args);
        }
    }

    record SelfRecurseCall(
            int functionId,
            List<HirExpr> args,
            ScopeAnalysis.ArgumentBinding binding,
            ExprMeta meta
    ) implements HirExpr {
        public SelfRecurseCall {
            args = List.copyOf(args);
        }
    }

    record DirectBabbleCall(
            HirExpr callee,
            int targetFunctionId,
            List<HirExpr> args,
            ScopeAnalysis.ArgumentBinding binding,
            ExprMeta meta
    ) implements HirExpr {
        public DirectBabbleCall {
            args = List.copyOf(args);
        }
    }

    record DirectThisMethodCall(
            HirExpr receiver,
            String receiverText,
            int classId,
            String memberName,
            int overloadAltIndex,
            int targetFunctionId,
            List<HirCallArg> args,
            ScopeAnalysis.ArgumentBinding binding,
            CallSite callSite,
            ExprMeta meta
    ) implements HirExpr {
        public DirectThisMethodCall {
            args = List.copyOf(args);
        }

        public DirectThisMethodCall(HirExpr receiver, int classId, String memberName, int targetFunctionId,
                                    List<HirCallArg> args, ScopeAnalysis.ArgumentBinding binding,
                                    CallSite callSite, ExprMeta meta) {
            this(receiver, null, classId, memberName, -1, targetFunctionId, args, binding, callSite, meta);
        }
    }

    record InterfaceCall(
            HirExpr receiver,
            String receiverText,
            int selectorLine,
            String interfaceTypeId,
            String memberName,
            ScopeAnalysis.FunctionSignature signature,
            List<HirExpr> args,
            ScopeAnalysis.ArgumentBinding binding,
            ExprMeta meta
    ) implements HirExpr {
        public InterfaceCall {
            args = List.copyOf(args);
        }
    }

    record JavaConstructorCall(
            HirExpr callee,
            String calleeText,
            boolean pureStaticCallee,
            ScopeAnalysis.JavaConstructorTarget target,
            List<HirExpr> args,
            ExprMeta meta
    ) implements HirExpr {
        public JavaConstructorCall {
            args = List.copyOf(args);
        }
    }

    record JavaMethodCall(
            HirExpr receiver,
            String receiverText,
            int selectorLine,
            boolean pureStaticReceiver,
            ScopeAnalysis.JavaCallTarget target,
            List<HirExpr> args,
            ExprMeta meta
    ) implements HirExpr {
        public JavaMethodCall {
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
            ScopeAnalysis.ArgumentBinding binding,
            ScopeAnalysis.FunctionSignature babbleSig,
            CallSite callSite,
            ExprMeta meta
    ) implements HirExpr {
        public SafeCall {
            args = List.copyOf(args);
        }
    }

    record DynamicCall(
            HirExpr callee,
            List<HirCallArg> args,
            ScopeAnalysis.ArgumentBinding binding,
            ScopeAnalysis.FunctionSignature babbleSig,
            CallSite callSite,
            ExprMeta meta
    ) implements HirExpr {
        public DynamicCall {
            args = List.copyOf(args);
        }
    }

    // ---------------------------------------------------------------- overload sets (BIP021-P5)

    record MakeOverloadSet(
            List<HirExpr> operands,
            List<ScopeAnalysis.OverloadAltMetaInfo> metas,
            ExprMeta meta
    ) implements HirExpr {
        public MakeOverloadSet {
            operands = List.copyOf(operands);
            metas = List.copyOf(metas);
        }
    }

    record OverloadSelect(
            HirExpr set,
            int index,
            String signatureKey,
            boolean exactSet,
            ExprMeta meta
    ) implements HirExpr {
    }

    record OverloadBranch(
            List<ScopeAnalysis.OverloadSplitCheck> checks,
            int directFunctionId,
            int altIndex,
            String signatureKey,
            boolean exactSet,
            ScopeAnalysis.FunctionSignature signature,
            ScopeAnalysis.ArgumentBinding binding,
            ScopeAnalysis.PrimitiveKind resultKind
    ) {
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
            ExprMeta meta
    ) implements HirExpr {
        public OverloadDispatch {
            args = List.copyOf(args);
            branches = List.copyOf(branches);
        }
    }

    // ---------------------------------------------------------------- value constructors

    record FunctionRef(int functionId, ExprMeta meta) implements HirExpr {
    }

    record ClassExpr(int classId, ExprMeta meta) implements HirExpr {
    }

    record InterfaceExpr(int interfaceId, ExprMeta meta) implements HirExpr {
    }

    record TypeApply(HirExpr inner, ExprMeta meta) implements HirExpr {
    }
}
