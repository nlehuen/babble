package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A class, object or interface expression, as the analysis sees it.
 */
final class TypeNode {
    static final class Member {
        final String name;
        int flags;
        final int index;
        boolean hasFunction;
        boolean isOverload;
        ParserRuleContext declaration;
        /** The function literal of a method or default, or null. */
        BabbleParser.FunctionLiteralContext function;
        /** Direct function literal operands when this member is an {@code overload (...)} method or default. */
        List<BabbleParser.FunctionLiteralContext> overloadFunctions;

        Member(String name, int flags, int index, ParserRuleContext declaration, BabbleParser.FunctionLiteralContext function) {
            this(name, flags, index, declaration, function, false, List.of());
        }

        Member(String name, int flags, int index, ParserRuleContext declaration, BabbleParser.FunctionLiteralContext function,
               boolean isOverload, List<BabbleParser.FunctionLiteralContext> overloadFunctions) {
            this.name = name;
            this.flags = flags;
            this.index = index;
            this.isOverload = isOverload;
            this.hasFunction = function != null || isOverload;
            this.declaration = declaration;
            this.function = function;
            this.overloadFunctions = overloadFunctions != null ? List.copyOf(overloadFunctions) : List.of();
        }

        void demoteSamField() {
            this.flags &= ~ClassShape.METHOD;
            this.hasFunction = false;
            this.isOverload = false;
            this.function = null;
            this.overloadFunctions = List.of();
        }

        boolean isMethod() {
            return (flags & ClassShape.METHOD) != 0;
        }

        boolean isPrivate() {
            return (flags & ClassShape.PRIVATE) != 0;
        }

        void detachAst() {
            this.declaration = null;
            this.function = null;
            this.overloadFunctions = List.of();
        }
    }

    private static final AtomicLong IDS = new AtomicLong();

    /** An id unique within the JVM, for a type of a script that is not a module. */
    static String nextId() {
        return "T" + IDS.incrementAndGet();
    }

    ParserRuleContext ctx;
    /**
     * The nominal type the expression defines (T9). In a module, its key and the type's name,
     * {@code lib/vec3.ba#Vec3}, the same every time the module is compiled ; elsewhere, unique
     * within the JVM (docs/design/bip006_module-sources-and-aot.md §5.2).
     */
    final String id;
    final ClassShape.Kind kind;
    final String name;
    /** Where the expression is evaluated. */
    ScopeNode scope;
    List<BabbleParser.TypeContext> parentTypes;
    final List<String> typeParameters;

    final Map<String, Member> members = new LinkedHashMap<>();
    final Map<String, Member> statics = new LinkedHashMap<>();
    final List<String> parameters = new ArrayList<>();
    final List<Integer> parameterMembers = new ArrayList<>();
    int requiredParameters = 0;
    int keywordOnlyStart = -1;

    /** Resolved parents : TypeNode interfaces or Java interface classes. */
    final List<Object> parents = new ArrayList<>();
    /** For interfaces : the defaults it provides, its own and inherited ones. */
    Map<String, TypeChecks.Provider> defaults;
    /** The declared types of members and statics, by name, as Types descriptors. */
    final Map<String, String> memberTypes = new LinkedHashMap<>();
    final Map<String, String> staticTypes = new LinkedHashMap<>();
    /** Converged lowering facts preserved across {@code detachAst()} for cross-module direct dispatch ({@code BIP014-I4}). */
    boolean fastRecord;
    PrimitiveKind[] fastRecordParamKinds;
    final Map<String, PrimitiveKind> memberKinds = new LinkedHashMap<>();
    final java.util.Set<String> definiteFields = new java.util.LinkedHashSet<>();
    final Map<String, LoweringTargets.FunctionSignature> methodSignatures = new LinkedHashMap<>();
    final Map<String, LoweringTargets.FunctionSignature> overloadMethodSignatures = new LinkedHashMap<>();
    boolean resolving;

    TypeNode(ParserRuleContext ctx, String id, ClassShape.Kind kind, String name, ScopeNode scope, List<BabbleParser.TypeContext> parentTypes) {
        this.ctx = ctx;
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.scope = scope;
        this.parentTypes = parentTypes;
        if (ctx.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == ctx && def.typeParameters() != null) {
            this.typeParameters = ScopeAnalysis.typeParamIds(def.typeParameters()).stream().map(Object::toString).toList();
        } else {
            this.typeParameters = List.of();
        }
    }

    boolean isInterface() {
        return kind == ClassShape.Kind.INTERFACE;
    }

    int line() {
        return ctx != null ? ctx.getStart().getLine() : 0;
    }

    void detachAst() {
        this.ctx = null;
        this.scope = null;
        this.parentTypes = List.of();
        for (Member m : members.values()) {
            m.detachAst();
        }
        for (Member m : statics.values()) {
            m.detachAst();
        }
    }

    ClassShape shape() {
        String[] memberNames = new String[members.size()];
        int[] flags = new int[members.size()];
        String[] memberTypeDescriptors = new String[members.size()];
        for (Member member : members.values()) {
            memberNames[member.index] = member.name;
            flags[member.index] = member.flags;
            memberTypeDescriptors[member.index] = memberTypes.get(member.name);
        }
        String[] staticNames = new String[statics.size()];
        int[] staticFlags = new int[statics.size()];
        String[] staticTypeDescriptors = new String[statics.size()];
        for (Member member : statics.values()) {
            staticNames[member.index] = member.name;
            staticFlags[member.index] = member.flags;
            staticTypeDescriptors[member.index] = staticTypes.get(member.name);
        }
        int kwStart = keywordOnlyStart >= 0 ? keywordOnlyStart : parameters.size();
        return new ClassShape(kind, name, memberNames, flags, parameters.toArray(new String[0]),
                parameterMembers.stream().mapToInt(Integer::intValue).toArray(), requiredParameters, kwStart, staticNames, staticFlags,
                id, memberTypeDescriptors, staticTypeDescriptors);
    }
}
