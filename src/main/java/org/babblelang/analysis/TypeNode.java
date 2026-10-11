package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A class, object or interface expression, as the analysis sees it.
 */
final class TypeNode {
    static final class Member {
        final String name;
        final int flags;
        final int index;
        final ParserRuleContext declaration;
        /** The function literal of a method or default, or null. */
        final BabbleParser.FunctionLiteralContext function;

        Member(String name, int flags, int index, ParserRuleContext declaration, BabbleParser.FunctionLiteralContext function) {
            this.name = name;
            this.flags = flags;
            this.index = index;
            this.declaration = declaration;
            this.function = function;
        }

        boolean isMethod() {
            return (flags & ClassShape.METHOD) != 0;
        }

        boolean isPrivate() {
            return (flags & ClassShape.PRIVATE) != 0;
        }
    }

    final ParserRuleContext ctx;
    final ClassShape.Kind kind;
    final String name;
    /** Where the expression is evaluated. */
    final ScopeNode scope;
    final List<BabbleParser.TypeContext> parentTypes;

    final Map<String, Member> members = new LinkedHashMap<>();
    final Map<String, Member> statics = new LinkedHashMap<>();
    final List<String> parameters = new ArrayList<>();
    final List<Integer> parameterMembers = new ArrayList<>();
    int requiredParameters = 0;

    /** Resolved parents : TypeNode interfaces or Java interface classes. */
    final List<Object> parents = new ArrayList<>();
    /** For interfaces : the defaults it provides, its own and inherited ones. */
    Map<String, TypeChecks.Provider> defaults;
    boolean resolving;

    TypeNode(ParserRuleContext ctx, ClassShape.Kind kind, String name, ScopeNode scope, List<BabbleParser.TypeContext> parentTypes) {
        this.ctx = ctx;
        this.kind = kind;
        this.name = name;
        this.scope = scope;
        this.parentTypes = parentTypes;
    }

    boolean isInterface() {
        return kind == ClassShape.Kind.INTERFACE;
    }

    int line() {
        return ctx.getStart().getLine();
    }

    ClassShape shape() {
        String[] memberNames = new String[members.size()];
        int[] flags = new int[members.size()];
        for (Member member : members.values()) {
            memberNames[member.index] = member.name;
            flags[member.index] = member.flags;
        }
        String[] staticNames = new String[statics.size()];
        int[] staticFlags = new int[statics.size()];
        for (Member member : statics.values()) {
            staticNames[member.index] = member.name;
            staticFlags[member.index] = member.flags;
        }
        return new ClassShape(kind, name, memberNames, flags, parameters.toArray(new String[0]),
                parameterMembers.stream().mapToInt(Integer::intValue).toArray(), requiredParameters, staticNames, staticFlags);
    }
}
