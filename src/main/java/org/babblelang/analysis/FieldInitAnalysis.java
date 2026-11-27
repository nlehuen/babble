package org.babblelang.analysis;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

/**
 * Static definite-initialization, single-assignment ({@code def} fields), and acyclic {@code
 * this(...)} delegation analysis for {@code BIP040} constructors.
 *
 * <p>Runs right after {@link TypeChecker} (when {@code Type.NOTHING} reachability and resolved
 * {@code this(...)} constructor targets are known) and before {@link EffectChecker} and {@link
 * LoweringAnalyzer}.
 */
final class FieldInitAnalysis {
    private final BlockScopes pass;
    private final TypeChecker checker;
    private BitSet suppressedMissing;

    private FieldInitAnalysis(BlockScopes pass, TypeChecker checker) {
        this.pass = pass;
        this.checker = checker;
    }

    static void run(BlockScopes pass, BabbleParser.FileContext file, TypeChecker checker) {
        FieldInitAnalysis analysis = new FieldInitAnalysis(pass, checker);
        for (TypeNode node : pass.types) {
            if (node.kind == ClassShape.Kind.INTERFACE) {
                continue;
            }
            if (node.kind == ClassShape.Kind.OBJECT) {
                analysis.checkObject(node);
            } else if (node.ctx instanceof BabbleParser.ClassExpressionContext) {
                analysis.checkClass(node);
            }
        }
    }

    private void checkObject(TypeNode node) {
        if (node.objectInit == null) {
            // Inline-only object: check whether inline field initializers escape `this`.
            return;
        }
        FieldMap fm = FieldMap.of(node);
        BitSet da = fm.initialDa();
        BitSet du = fm.initialDu();
        boolean[] thisUsedBeforeAll = {false};
        boolean[] defFinalSafe = {true};
        InitState end =
                walkBlock(
                        node,
                        null,
                        fm,
                        node.objectInit.initBlock,
                        new InitState(da, du, false),
                        0,
                        false,
                        thisUsedBeforeAll,
                        defFinalSafe);
        if (!end.unreachable) {
            checkAllAssigned(node, fm, end.da, node.objectInit);
        }
    }

    private void checkClass(TypeNode node) {
        FieldMap fm = FieldMap.of(node);
        boolean isValue = node.kind == ClassShape.Kind.VALUE_CLASS;
        boolean hasPrimaryHeader =
                node.ctx instanceof BabbleParser.ClassExpressionContext cls
                        && cls.classParameters() != null
                        && !cls.classParameters().classParam().isEmpty();

        // Phase 1: classify delegation (`this(...)`) on every parameterized `init` constructor.
        for (TypeNode.Constructor ctor : node.constructors) {
            if (ctor.isPrimary) {
                if (ctor.initBlock != null) {
                    rejectDelegatingCallInPrimaryInit(ctor.initBlock.initBlock);
                }
                continue;
            }
            BabbleParser.InitExpressionContext initCtx = ctor.initBlock;
            if (initCtx == null) {
                continue;
            }
            DelegationScan scan = scanDelegation(node, ctor, initCtx.initBlock);
            if (scan.hasInvalid) {
                ctor.delegateIndex = -2;
                continue;
            }
            if (scan.hasMixedPaths) {
                checker.warn(
                        "Constructor must either delegate on all paths or initialize fields directly",
                        initCtx);
                ctor.delegateIndex = -2;
                continue;
            }
            if (scan.delegates) {
                ctor.delegateCall = scan.delegateCall;
                ctor.delegateStmtIndex = scan.delegateStmtIndex;
                Integer target = checker.resolvedConstructorIndex(scan.delegateCall);
                ctor.delegateIndex = target != null ? target : -1;
                if (isValue && scan.hasPostDelegateStatements) {
                    checker.warn(
                            "Record delegating init cannot have statements after this(...)",
                            scan.firstPostDelegateStmt);
                }
            } else if (hasPrimaryHeader || isValue) {
                checker.warn("Secondary init constructor must delegate to this(...)", initCtx);
            }
        }

        // Phase 2: detect cycles in `this(...)` delegation graph.
        int n = node.constructors.size();
        int[] color = new int[n]; // 0=unvisited, 1=visiting, 2=done
        for (int i = 0; i < n; i++) {
            if (color[i] == 0) {
                checkDelegationCycle(node, i, color);
            }
        }

        // Phase 3: definite-assignment & single-assignment check for each constructor.
        for (TypeNode.Constructor ctor : node.constructors) {
            if (ctor.delegateIndex == -2) {
                continue;
            }
            if (ctor.delegateIndex >= 0) {
                // Delegating constructor: pre-delegation statements cannot reference `this`;
                // post-delegation statements see all fields already initialized (`DA = ALL`, `DU =
                // 0`).
                if (ctor.initBlock != null) {
                    checkDelegatingBody(node, ctor, fm, ctor.initBlock.initBlock);
                }
            } else {
                // Root constructor (primary with optional `init (...)`, or non-delegating explicit
                // `init`).
                if (ctor.initBlock != null) {
                    BitSet da = fm.initialDa();
                    BitSet du = fm.initialDu();
                    boolean[] thisUsedBeforeAll = {false};
                    boolean[] defFinalSafe = {true};
                    this.suppressedMissing = new BitSet(fm.count);
                    InitState end =
                            walkBlock(
                                    node,
                                    ctor,
                                    fm,
                                    ctor.initBlock.initBlock,
                                    new InitState(da, du, false),
                                    0,
                                    false,
                                    thisUsedBeforeAll,
                                    defFinalSafe);
                    if (!end.unreachable) {
                        BitSet effectiveDa = (BitSet) end.da.clone();
                        effectiveDa.or(this.suppressedMissing);
                        checkAllAssigned(node, fm, effectiveDa, ctor.initBlock);
                    }
                    this.suppressedMissing = null;
                    ctor.allDefFieldsFinalSafe = defFinalSafe[0] && !thisUsedBeforeAll[0];
                } else {
                    // Header/inline-only constructor (no `init` block): safe unless inline
                    // initializers touch `this` before all fields are initialized.
                    ctor.allDefFieldsFinalSafe = true;
                }
            }
        }
        BabbleParser.ClassExpressionContext clsCtx =
                node.ctx instanceof BabbleParser.ClassExpressionContext c ? c : null;
        boolean inlineSafe = clsCtx == null || isInlineDefFinalSafe(node, fm, clsCtx.classBlock);
        boolean allSafe = inlineSafe;
        for (TypeNode.Constructor ctor : node.constructors) {
            if (!ctor.allDefFieldsFinalSafe) {
                allSafe = false;
                break;
            }
        }
        if (allSafe && clsCtx != null && checker.inFile(clsCtx)) {
            pass.analysis.lowering().markAllDefFieldsFinalSafe(clsCtx);
        }
    }

    private boolean isInlineDefFinalSafe(
            TypeNode node, FieldMap fm, BabbleParser.BlockContext classBlock) {
        if (classBlock == null) {
            return true;
        }
        BitSet initialized = new BitSet(fm.count);
        for (int i = 0; i < fm.count; i++) {
            if (node.parameterMembers.contains(fm.memberIndex[i])) {
                initialized.set(i);
            }
        }
        for (BabbleParser.ExpressionContext stmt : classBlock.expression()) {
            if (!(stmt instanceof BabbleParser.DefExpressionContext def)
                    || def.value == null
                    || def.value instanceof BabbleParser.FunctionLiteralContext
                    || def.value instanceof BabbleParser.OverloadExpressionContext) {
                continue;
            }
            if (exprUsesUnsafeThis(def.value, fm, initialized)) {
                return false;
            }
            Integer slot = fm.fieldBit.get(def.name.getText());
            if (slot != null) {
                initialized.set(slot);
            }
        }
        return true;
    }

    private boolean exprUsesUnsafeThis(
            org.antlr.v4.runtime.tree.ParseTree tree, FieldMap fm, BitSet initialized) {
        if (tree == null
                || tree instanceof BabbleParser.FunctionLiteralContext
                || tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
                || tree instanceof BabbleParser.InterfaceExpressionContext) {
            return false;
        }
        if (tree instanceof BabbleParser.SelectorContext sel
                && sel.expression() instanceof BabbleParser.ThisExpressionContext) {
            Integer slot = fm.fieldBit.get(ScopeAnalysis.selectorName(sel));
            return slot == null || !initialized.get(slot);
        }
        if (tree instanceof BabbleParser.ThisExpressionContext) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (exprUsesUnsafeThis(tree.getChild(i), fm, initialized)) {
                return true;
            }
        }
        return false;
    }

    private void checkDelegationCycle(TypeNode node, int idx, int[] color) {
        color[idx] = 1;
        TypeNode.Constructor ctor = node.constructors.get(idx);
        int next = ctor.delegateIndex;
        if (next >= 0 && next < node.constructors.size()) {
            if (color[next] == 1) {
                ParserRuleContext at = ctor.delegateCall != null ? ctor.delegateCall : ctor.ctx;
                checker.warn("Cyclic constructor delegation", at);
            } else if (color[next] == 0) {
                checkDelegationCycle(node, next, color);
            }
        }
        color[idx] = 2;
    }

    private void rejectDelegatingCallInPrimaryInit(BabbleParser.BlockContext block) {
        findCallsToThis(
                block,
                call ->
                        checker.warn(
                                "Cannot call this(...) inside a parameterless init block", call));
    }

    private record DelegationScan(
            boolean delegates,
            boolean hasMixedPaths,
            boolean hasInvalid,
            BabbleParser.CallContext delegateCall,
            int delegateStmtIndex,
            boolean hasPostDelegateStatements,
            ParserRuleContext firstPostDelegateStmt) {}

    private DelegationScan scanDelegation(
            TypeNode node, TypeNode.Constructor ctor, BabbleParser.BlockContext block) {
        List<BabbleParser.ExpressionContext> stmts = block.expression();
        BabbleParser.CallContext topDelegate = null;
        int topIndex = -1;
        boolean foundInvalid = false;

        for (int i = 0; i < stmts.size(); i++) {
            BabbleParser.ExpressionContext stmt = stmts.get(i);
            if (isDirectThisCall(stmt)) {
                BabbleParser.CallContext call = (BabbleParser.CallContext) stmt;
                if (topDelegate != null) {
                    checker.warn(
                            "Constructor must either delegate on all paths or initialize fields directly",
                            call);
                    foundInvalid = true;
                } else {
                    topDelegate = call;
                    topIndex = i;
                }
                // Check delegation arguments don't reference `this`.
                for (BabbleParser.CallParameterContext arg :
                        call.callParameters().callParameter()) {
                    checkNoThisRef(
                            arg.expression(), "Cannot use this before delegating constructor call");
                }
            } else {
                // Check for nested `this(...)` inside loops, closures, or sub-expressions.
                boolean[] nestedFound = {false};
                findNestedThisCalls(
                        stmt,
                        false,
                        (call, inLoopOrClosure) -> {
                            nestedFound[0] = true;
                            if (inLoopOrClosure) {
                                checker.warn(
                                        "Delegating constructor call cannot appear inside a loop or closure",
                                        call);
                            } else {
                                checker.warn(
                                        "Constructor must either delegate on all paths or initialize fields directly",
                                        call);
                            }
                        });
                if (nestedFound[0]) {
                    foundInvalid = true;
                }
                if (topDelegate == null) {
                    // Pre-delegation statement: if a later statement delegates, we'll verify no
                    // `this` ref.
                }
            }
        }
        if (foundInvalid) {
            return new DelegationScan(false, false, true, null, -1, false, null);
        }
        if (topDelegate != null) {
            for (int i = 0; i < topIndex; i++) {
                checkNoThisRef(stmts.get(i), "Cannot use this before delegating constructor call");
            }
            boolean hasPost = topIndex + 1 < stmts.size();
            return new DelegationScan(
                    true,
                    false,
                    false,
                    topDelegate,
                    topIndex,
                    hasPost,
                    hasPost ? stmts.get(topIndex + 1) : null);
        }
        return new DelegationScan(false, false, false, null, -1, false, null);
    }

    private void checkDelegatingBody(
            TypeNode node,
            TypeNode.Constructor ctor,
            FieldMap fm,
            BabbleParser.BlockContext block) {
        List<BabbleParser.ExpressionContext> stmts = block.expression();
        int delIdx = ctor.delegateStmtIndex;
        if (delIdx < 0 || delIdx + 1 >= stmts.size()) {
            return;
        }
        // After delegation, all fields are definitely assigned (`DA = all`, `DU = empty`).
        BitSet all = fm.allFields();
        BitSet empty = new BitSet(fm.count);
        InitState state = new InitState(all, empty, false);
        boolean[] dummy1 = {false};
        boolean[] dummy2 = {true};
        for (int i = delIdx + 1; i < stmts.size(); i++) {
            state = walkExpr(node, ctor, fm, stmts.get(i), state, 0, false, dummy1, dummy2);
        }
    }

    private void checkNoThisRef(ParserRuleContext tree, String message) {
        if (tree == null) {
            return;
        }
        if (tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
                || tree instanceof BabbleParser.InterfaceExpressionContext) {
            return;
        }
        if (tree instanceof BabbleParser.ThisExpressionContext) {
            checker.warn(message, tree);
            return;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (tree.getChild(i) instanceof ParserRuleContext child) {
                checkNoThisRef(child, message);
            }
        }
    }

    private static boolean isDirectThisCall(BabbleParser.ExpressionContext expr) {
        return expr instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.ThisExpressionContext;
    }

    private interface NestedThisCallVisitor {
        void onCall(BabbleParser.CallContext call, boolean inLoopOrClosure);
    }

    private static void findNestedThisCalls(
            ParserRuleContext tree, boolean inLoopOrClosure, NestedThisCallVisitor visitor) {
        if (tree == null
                || tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
                || tree instanceof BabbleParser.InterfaceExpressionContext) {
            return;
        }
        if (tree instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.ThisExpressionContext) {
            visitor.onCall(call, inLoopOrClosure);
            return;
        }
        boolean nextInLoopOrClosure =
                inLoopOrClosure
                        || tree instanceof BabbleParser.WhileExpressionContext
                        || tree instanceof BabbleParser.ForExpressionContext
                        || tree instanceof BabbleParser.FunctionLiteralContext;
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (tree.getChild(i) instanceof ParserRuleContext child) {
                findNestedThisCalls(child, nextInLoopOrClosure, visitor);
            }
        }
    }

    private static void findCallsToThis(
            ParserRuleContext tree,
            java.util.function.Consumer<BabbleParser.CallContext> consumer) {
        if (tree == null
                || tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
                || tree instanceof BabbleParser.InterfaceExpressionContext
                || tree instanceof BabbleParser.FunctionLiteralContext) {
            return;
        }
        if (tree instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.ThisExpressionContext) {
            consumer.accept(call);
            return;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (tree.getChild(i) instanceof ParserRuleContext child) {
                findCallsToThis(child, consumer);
            }
        }
    }

    // ---------------------------------------------------------------- DA/DU dataflow

    private static final class FieldMap {
        final int count;
        final Map<String, Integer> fieldBit = new LinkedHashMap<>();
        final String[] fieldName;
        final int[] memberIndex;
        final boolean[] isDef;
        final boolean[] isInitiallyAssigned;

        private FieldMap(
                int count,
                String[] fieldName,
                int[] memberIndex,
                boolean[] isDef,
                boolean[] isInitiallyAssigned) {
            this.count = count;
            this.fieldName = fieldName;
            this.memberIndex = memberIndex;
            this.isDef = isDef;
            this.isInitiallyAssigned = isInitiallyAssigned;
            for (int i = 0; i < count; i++) {
                fieldBit.put(fieldName[i], i);
            }
        }

        static FieldMap of(TypeNode node) {
            List<TypeNode.Member> fields = new java.util.ArrayList<>();
            for (TypeNode.Member m : node.members.values()) {
                if (!m.isMethod()) {
                    fields.add(m);
                }
            }
            int n = fields.size();
            String[] names = new String[n];
            int[] memIdx = new int[n];
            boolean[] isDef = new boolean[n];
            boolean[] initAssigned = new boolean[n];
            for (int i = 0; i < n; i++) {
                TypeNode.Member m = fields.get(i);
                names[i] = m.name;
                memIdx[i] = m.index;
                isDef[i] = !m.isMutable();
                initAssigned[i] = !node.uninitializedFields.contains(m.name);
            }
            return new FieldMap(n, names, memIdx, isDef, initAssigned);
        }

        BitSet initialDa() {
            BitSet b = new BitSet(count);
            for (int i = 0; i < count; i++) {
                if (isInitiallyAssigned[i]) {
                    b.set(i);
                }
            }
            return b;
        }

        BitSet initialDu() {
            BitSet b = new BitSet(count);
            for (int i = 0; i < count; i++) {
                if (!isInitiallyAssigned[i]) {
                    b.set(i);
                }
            }
            return b;
        }

        BitSet allFields() {
            BitSet b = new BitSet(count);
            b.set(0, count);
            return b;
        }

        boolean allAssigned(BitSet da) {
            return da.cardinality() == count;
        }
    }

    private record InitState(BitSet da, BitSet du, boolean unreachable) {
        InitState copy() {
            return new InitState((BitSet) da.clone(), (BitSet) du.clone(), unreachable);
        }

        static InitState join(InitState a, InitState b) {
            if (a.unreachable) {
                return b.copy();
            }
            if (b.unreachable) {
                return a.copy();
            }
            BitSet da = (BitSet) a.da.clone();
            da.and(b.da);
            BitSet du = (BitSet) a.du.clone();
            du.and(b.du);
            return new InitState(da, du, false);
        }
    }

    private void checkAllAssigned(TypeNode node, FieldMap fm, BitSet da, ParserRuleContext at) {
        for (int i = 0; i < fm.count; i++) {
            if (!da.get(i)) {
                checker.warn("Field may not be initialized by init : " + fm.fieldName[i], at);
            }
        }
    }

    private InitState walkBlock(
            TypeNode node,
            TypeNode.Constructor ctor,
            FieldMap fm,
            BabbleParser.BlockContext block,
            InitState state,
            int loopDepth,
            boolean inClosure,
            boolean[] thisUsedBeforeAll,
            boolean[] defFinalSafe) {
        InitState cur = state.copy();
        for (BabbleParser.ExpressionContext stmt : block.expression()) {
            cur =
                    walkExpr(
                            node,
                            ctor,
                            fm,
                            stmt,
                            cur,
                            loopDepth,
                            inClosure,
                            thisUsedBeforeAll,
                            defFinalSafe);
        }
        return cur;
    }

    private InitState walkExpr(
            TypeNode node,
            TypeNode.Constructor ctor,
            FieldMap fm,
            ParserRuleContext expr,
            InitState state,
            int loopDepth,
            boolean inClosure,
            boolean[] thisUsedBeforeAll,
            boolean[] defFinalSafe) {
        if (expr == null) {
            return state;
        }
        switch (expr) {
            case BabbleParser.ClassExpressionContext ignored -> {
                return state;
            }
            case BabbleParser.InterfaceExpressionContext ignored -> {
                return state;
            }
            case BabbleParser.ObjectExpressionContext ignored -> {
                return state;
            }
            case BabbleParser.FunctionLiteralContext fn -> {
                // Closure inside `init`: if it mentions `this`, all fields must already be
                // initialized;
                // and assigning any `def` field inside a closure is always forbidden.
                if (containsThisReference(fn.functionBlock)) {
                    if (node.kind == ClassShape.Kind.VALUE_CLASS) {
                        checker.warn("this cannot escape a value class under construction", fn);
                    } else if (!fm.allAssigned(state.da)) {
                        thisUsedBeforeAll[0] = true;
                        checker.warn("Cannot use this before all fields are initialized", fn);
                    }
                }
                walkBlock(
                        node,
                        ctor,
                        fm,
                        fn.functionBlock,
                        state.copy(),
                        loopDepth,
                        true,
                        thisUsedBeforeAll,
                        defFinalSafe);
                return state;
            }
            case BabbleParser.BlockExpressionContext blk -> {
                return walkBlock(
                        node,
                        ctor,
                        fm,
                        blk.block(),
                        state,
                        loopDepth,
                        inClosure,
                        thisUsedBeforeAll,
                        defFinalSafe);
            }
            case BabbleParser.BlockContext blk -> {
                return walkBlock(
                        node,
                        ctor,
                        fm,
                        blk,
                        state,
                        loopDepth,
                        inClosure,
                        thisUsedBeforeAll,
                        defFinalSafe);
            }
            case BabbleParser.AssignExpressionContext assign -> {
                BabbleParser.ExpressionContext recv = ScopeAnalysis.assignReceiver(assign);
                String name = ScopeAnalysis.assignName(assign);
                if (recv instanceof BabbleParser.ThisExpressionContext) {
                    Integer bit = fm.fieldBit.get(name);
                    boolean compound = !assign.op.getText().equals("=");
                    if (bit != null) {
                        if (compound) {
                            if (!state.da.get(bit)) {
                                checker.warn(
                                        "Field used before initialization : " + name, assign.lhs);
                            }
                            if (fm.isDef[bit]) {
                                checker.warn("Cannot assign to def field : " + name, assign.lhs);
                            }
                        }
                        InitState afterRhs =
                                walkExpr(
                                        node,
                                        ctor,
                                        fm,
                                        assign.value,
                                        state,
                                        loopDepth,
                                        inClosure,
                                        thisUsedBeforeAll,
                                        defFinalSafe);
                        if (!compound) {
                            if (fm.isDef[bit]) {
                                if (inClosure) {
                                    checker.warn("Cannot assign to def : " + name, assign.lhs);
                                } else if (loopDepth > 0) {
                                    defFinalSafe[0] = false;
                                    checker.warn(
                                            "Cannot assign to def field in a loop : " + name,
                                            assign.lhs);
                                    if (suppressedMissing != null) {
                                        suppressedMissing.set(bit);
                                    }
                                } else if (!afterRhs.du.get(bit)) {
                                    defFinalSafe[0] = false;
                                    if (node.kind == ClassShape.Kind.VALUE_CLASS
                                            && fm.isInitiallyAssigned[bit]) {
                                        checker.warn(
                                                "Value members are read-only : " + name,
                                                assign.lhs);
                                    } else {
                                        checker.warn(
                                                "Cannot assign to def field : " + name, assign.lhs);
                                    }
                                } else {
                                    pass.analysis
                                            .lowering()
                                            .markThisFieldInit(assign, fm.memberIndex[bit]);
                                }
                            } else if (!afterRhs.da.get(bit) && !inClosure) {
                                pass.analysis
                                        .lowering()
                                        .markThisFieldInit(assign, fm.memberIndex[bit]);
                            }
                            InitState next = afterRhs.copy();
                            next.da.set(bit);
                            next.du.clear(bit);
                            return next;
                        }
                        return afterRhs;
                    }
                }
                InitState cur = state;
                if (recv != null) {
                    cur =
                            walkExpr(
                                    node,
                                    ctor,
                                    fm,
                                    recv,
                                    cur,
                                    loopDepth,
                                    inClosure,
                                    thisUsedBeforeAll,
                                    defFinalSafe);
                }
                return walkExpr(
                        node,
                        ctor,
                        fm,
                        assign.value,
                        cur,
                        loopDepth,
                        inClosure,
                        thisUsedBeforeAll,
                        defFinalSafe);
            }
            case BabbleParser.SelectorContext sel -> {
                if (sel.expression() instanceof BabbleParser.ThisExpressionContext) {
                    String name = ScopeAnalysis.selectorName(sel);
                    Integer bit = fm.fieldBit.get(name);
                    if (bit != null) {
                        if (!state.da.get(bit)) {
                            checker.warn("Field used before initialization : " + name, sel);
                        }
                        return state;
                    }
                    // Method selection on `this` (e.g. `this.foo()` or `def f = this.foo`):
                    if (node.kind == ClassShape.Kind.VALUE_CLASS) {
                        checker.warn("this cannot escape a value class under construction", sel);
                    } else if (!fm.allAssigned(state.da)) {
                        thisUsedBeforeAll[0] = true;
                        checker.warn("Cannot use this before all fields are initialized", sel);
                    }
                    return state;
                }
                if (sel.expression() != null) {
                    return walkExpr(
                            node,
                            ctor,
                            fm,
                            sel.expression(),
                            state,
                            loopDepth,
                            inClosure,
                            thisUsedBeforeAll,
                            defFinalSafe);
                }
                return state;
            }
            case BabbleParser.ThisExpressionContext thisExpr -> {
                if (node.kind == ClassShape.Kind.VALUE_CLASS) {
                    checker.warn("this cannot escape a value class under construction", thisExpr);
                } else if (!fm.allAssigned(state.da)) {
                    thisUsedBeforeAll[0] = true;
                    checker.warn("Cannot use this before all fields are initialized", thisExpr);
                }
                return state;
            }
            case BabbleParser.IfExpressionContext ifWrap -> {
                return walkExpr(
                        node,
                        ctor,
                        fm,
                        ifWrap.ifExpr(),
                        state,
                        loopDepth,
                        inClosure,
                        thisUsedBeforeAll,
                        defFinalSafe);
            }
            case BabbleParser.IfExprContext ifCtx -> {
                InitState afterTest =
                        walkExpr(
                                node,
                                ctor,
                                fm,
                                ifCtx.test,
                                state,
                                loopDepth,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                InitState thenOut =
                        walkBlock(
                                node,
                                ctor,
                                fm,
                                ifCtx.thenBlock,
                                afterTest.copy(),
                                loopDepth,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                if (checker.types().get(ifCtx.thenBlock) == Type.NOTHING) {
                    thenOut = new InitState(thenOut.da, thenOut.du, true);
                }
                InitState elseOut;
                if (ifCtx.elseBlock != null) {
                    elseOut =
                            walkBlock(
                                    node,
                                    ctor,
                                    fm,
                                    ifCtx.elseBlock,
                                    afterTest.copy(),
                                    loopDepth,
                                    inClosure,
                                    thisUsedBeforeAll,
                                    defFinalSafe);
                    if (checker.types().get(ifCtx.elseBlock) == Type.NOTHING) {
                        elseOut = new InitState(elseOut.da, elseOut.du, true);
                    }
                } else if (ifCtx.elseIf != null) {
                    elseOut =
                            walkExpr(
                                    node,
                                    ctor,
                                    fm,
                                    ifCtx.elseIf,
                                    afterTest.copy(),
                                    loopDepth,
                                    inClosure,
                                    thisUsedBeforeAll,
                                    defFinalSafe);
                    if (checker.types().get(ifCtx.elseIf) == Type.NOTHING) {
                        elseOut = new InitState(elseOut.da, elseOut.du, true);
                    }
                } else {
                    elseOut = afterTest.copy();
                }
                return InitState.join(thenOut, elseOut);
            }
            case BabbleParser.MatchExpressionContext match -> {
                InitState cur =
                        walkExpr(
                                node,
                                ctor,
                                fm,
                                match.scrutinee,
                                state,
                                loopDepth,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                InitState merged = null;
                for (BabbleParser.CaseClauseContext cc : match.caseClause()) {
                    InitState armIn = cur.copy();
                    if (cc.guard != null) {
                        armIn =
                                walkExpr(
                                        node,
                                        ctor,
                                        fm,
                                        cc.guard,
                                        armIn,
                                        loopDepth,
                                        inClosure,
                                        thisUsedBeforeAll,
                                        defFinalSafe);
                    }
                    InitState armOut =
                            walkBlock(
                                    node,
                                    ctor,
                                    fm,
                                    cc.armBlock,
                                    armIn,
                                    loopDepth,
                                    inClosure,
                                    thisUsedBeforeAll,
                                    defFinalSafe);
                    if (checker.types().get(cc.armBlock) == Type.NOTHING) {
                        armOut = new InitState(armOut.da, armOut.du, true);
                    }
                    merged = merged == null ? armOut : InitState.join(merged, armOut);
                }
                if (match.elseClause() != null) {
                    InitState elseOut =
                            walkBlock(
                                    node,
                                    ctor,
                                    fm,
                                    match.elseClause().elseBlock,
                                    cur.copy(),
                                    loopDepth,
                                    inClosure,
                                    thisUsedBeforeAll,
                                    defFinalSafe);
                    if (checker.types().get(match.elseClause().elseBlock) == Type.NOTHING) {
                        elseOut = new InitState(elseOut.da, elseOut.du, true);
                    }
                    merged = merged == null ? elseOut : InitState.join(merged, elseOut);
                } else if (!pass.analysis.lowering().isMatchExhaustive(match)) {
                    merged = merged == null ? cur.copy() : InitState.join(merged, cur.copy());
                }
                return merged != null ? merged : cur;
            }
            case BabbleParser.TryExpressionContext tryCtx -> {
                InitState tryOut =
                        walkBlock(
                                node,
                                ctor,
                                fm,
                                tryCtx.tryBlock,
                                state.copy(),
                                loopDepth,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                if (checker.types().get(tryCtx.tryBlock) == Type.NOTHING) {
                    tryOut = new InitState(tryOut.da, tryOut.du, true);
                }
                BitSet assignedInTry = (BitSet) state.du.clone();
                assignedInTry.andNot(tryOut.du);
                BitSet catchDu = (BitSet) state.du.clone();
                catchDu.andNot(assignedInTry);
                InitState merged = tryOut;
                for (BabbleParser.CatchClauseContext cc : tryCtx.catchClause()) {
                    InitState catchIn =
                            new InitState(
                                    (BitSet) state.da.clone(), (BitSet) catchDu.clone(), false);
                    InitState catchOut =
                            walkBlock(
                                    node,
                                    ctor,
                                    fm,
                                    cc.catchBlock,
                                    catchIn,
                                    loopDepth,
                                    inClosure,
                                    thisUsedBeforeAll,
                                    defFinalSafe);
                    if (checker.types().get(cc.catchBlock) == Type.NOTHING) {
                        catchOut = new InitState(catchOut.da, catchOut.du, true);
                    }
                    merged = InitState.join(merged, catchOut);
                }
                if (tryCtx.finallyBlock != null) {
                    merged =
                            walkBlock(
                                    node,
                                    ctor,
                                    fm,
                                    tryCtx.finallyBlock,
                                    merged,
                                    loopDepth,
                                    inClosure,
                                    thisUsedBeforeAll,
                                    defFinalSafe);
                }
                return merged;
            }
            case BabbleParser.WhileExpressionContext wh -> {
                InitState afterTest =
                        walkExpr(
                                node,
                                ctor,
                                fm,
                                wh.test,
                                state,
                                loopDepth + 1,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                InitState bodyOut =
                        walkBlock(
                                node,
                                ctor,
                                fm,
                                wh.whileBlock,
                                afterTest.copy(),
                                loopDepth + 1,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                // Loop may run 0 times: DA stays at afterTest, DU shrinks by anything assigned in
                // body.
                BitSet du = (BitSet) afterTest.du.clone();
                du.and(bodyOut.du);
                return new InitState((BitSet) afterTest.da.clone(), du, false);
            }
            case BabbleParser.ForExpressionContext fr -> {
                InitState afterIter =
                        walkExpr(
                                node,
                                ctor,
                                fm,
                                fr.iterable,
                                state,
                                loopDepth,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                InitState bodyOut =
                        walkBlock(
                                node,
                                ctor,
                                fm,
                                fr.forBlock,
                                afterIter.copy(),
                                loopDepth + 1,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                BitSet du = (BitSet) afterIter.du.clone();
                du.and(bodyOut.du);
                return new InitState((BitSet) afterIter.da.clone(), du, false);
            }
            case BabbleParser.ThrowExpressionContext thr -> {
                InitState after =
                        walkExpr(
                                node,
                                ctor,
                                fm,
                                thr.expression(),
                                state,
                                loopDepth,
                                inClosure,
                                thisUsedBeforeAll,
                                defFinalSafe);
                return new InitState(after.da, after.du, true);
            }
            case BabbleParser.DefExpressionContext def -> {
                if (def.value != null) {
                    return walkExpr(
                            node,
                            ctor,
                            fm,
                            def.value,
                            state,
                            loopDepth,
                            inClosure,
                            thisUsedBeforeAll,
                            defFinalSafe);
                }
                return state;
            }
            default -> {
                InitState cur = state;
                for (int i = 0; i < expr.getChildCount(); i++) {
                    if (expr.getChild(i) instanceof ParserRuleContext child) {
                        cur =
                                walkExpr(
                                        node,
                                        ctor,
                                        fm,
                                        child,
                                        cur,
                                        loopDepth,
                                        inClosure,
                                        thisUsedBeforeAll,
                                        defFinalSafe);
                    }
                }
                return cur;
            }
        }
    }

    private static boolean containsThisReference(ParserRuleContext tree) {
        if (tree == null
                || tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
                || tree instanceof BabbleParser.InterfaceExpressionContext) {
            return false;
        }
        if (tree instanceof BabbleParser.ThisExpressionContext) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (tree.getChild(i) instanceof ParserRuleContext child
                    && containsThisReference(child)) {
                return true;
            }
        }
        return false;
    }
}
