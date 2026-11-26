package org.babblelang.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What a file reads of other files ({@code BIP010-D3}) : the detached summaries of the earlier
 * inputs of its session and of every module it loads, directly or not. Classes, interfaces and
 * callables defined elsewhere are typed from these, never from another file's checker.
 */
final class ImportedSummaries {
    private final BlockScopes pass;
    private List<ModuleInterface> modules;

    ImportedSummaries(BlockScopes pass) {
        this.pass = pass;
    }

    /** Every module the file loads, directly or through other modules, in breadth-first order. */
    List<ModuleInterface> modules() {
        if (modules == null) {
            List<ModuleInterface> result = new ArrayList<>();
            Set<ModuleInterface> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<ModuleInterface> queue = new ArrayDeque<>(pass.modules.values());
            if (pass.session != null) {
                queue.addAll(pass.session.modules.values());
            }
            while (!queue.isEmpty()) {
                ModuleInterface mod = queue.poll();
                if (mod != null && seen.add(mod)) {
                    result.add(mod);
                    queue.addAll(mod.dependencies());
                }
            }
            modules = result;
        }
        return modules;
    }

    /**
     * The first answer of the session, then of the modules' published summaries ; null if none
     * knows.
     */
    private <T> T find(
            Function<AnalysisSession, T> fromSession, Function<TypedModuleSummary, T> fromModule) {
        if (pass.session != null) {
            T found = fromSession.apply(pass.session);
            if (found != null) {
                return found;
            }
        }
        for (ModuleInterface mod : modules()) {
            TypedModuleSummary summary = mod.summary();
            T found = summary != null ? fromModule.apply(summary) : null;
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static <T> T at(List<T> list, int index) {
        return list != null && index < list.size() ? list.get(index) : null;
    }

    private static Type member(Map<String, Type> members, String name) {
        return members != null ? members.get(name) : null;
    }

    List<Type.TypeVar> typeVars(TypeNode node) {
        return find(s -> s.typeNodeVars.get(node), s -> s.typeVarsOf(node));
    }

    Type parentType(TypeNode node, int index) {
        return find(s -> at(s.parentTypes.get(node), index), s -> at(s.parentTypesOf(node), index));
    }

    Type memberType(TypeNode owner, String name) {
        return find(
                s -> member(s.typeMemberTypes.get(owner), name), s -> s.memberType(owner, name));
    }

    List<Type.Parameter> constructorParams(TypeNode node) {
        return find(s -> s.constructorParams.get(node), s -> s.constructorParams(node));
    }

    EffectChecker.CallableSummary constructorSummary(TypeNode node) {
        return find(s -> s.constructorSummaries.get(node), s -> s.constructorSummary(node));
    }

    TypedModuleSummary.CallableEffectTemplate callableTemplate(Type.Function fn) {
        return find(s -> s.callableTemplates.get(fn), s -> s.callableTemplate(fn));
    }
}
