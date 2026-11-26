package org.babblelang.engine.impl;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Arguments;
import org.babblelang.runtime.BabbleFunction;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Frames;
import org.babblelang.runtime.Namespace;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

/** An interpreted function : a function literal and the namespace it closes over. */
public class Function implements BabbleFunction {
    private final BabbleParser.FunctionLiteralContext definition;
    private final Namespace closure;
    private final Interpreter interpreter;

    Function(
            BabbleParser.FunctionLiteralContext definition,
            Namespace namespace,
            Interpreter interpreter) {
        this.definition = definition;
        this.closure = namespace;
        this.interpreter = interpreter;
    }

    @Override
    public Object invoke(
            Object thiz, ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        // A self tail call (BIP024) ends an activation with the arguments of the next one.
        while (true) {
            Object result = activate(thiz, rt, args, names);
            if (!(result instanceof TailCall next)) {
                return result;
            }
            args = next.args();
            names = next.names();
        }
    }

    /** One activation of the function : a fresh frame, its parameters bound, and its body run. */
    private Object activate(Object thiz, ScriptRuntime rt, Object[] args, String[] names) {
        Namespace namespace =
                Frames.enter(
                        closure,
                        recurseTarget(thiz),
                        thiz,
                        interpreter.scopes().declared(definition),
                        interpreter.scopes().declaredFinals(definition));
        String[] parameters = interpreter.scopes().calls().parameters(definition);
        int restIdx = interpreter.scopes().calls().restIndex(definition);
        Arguments.Binder binder =
                Arguments.binder(
                        parameters,
                        interpreter.scopes().calls().keywordOnlyStart(definition),
                        restIdx,
                        args,
                        names);
        for (int i = 0; i < parameters.length; i++) {
            Object value = binder.take(i);
            if (value == Arguments.MISSING) {
                BabbleParser.ExpressionContext defaultValue =
                        definition.fnParameters().fnParam(i).defaultValue;
                if (defaultValue == null) {
                    throw binder.missing(i);
                }
                // A default value belongs to the function's scope, not to the call site.
                value = interpreter.evaluateDefault(namespace, defaultValue);
            }
            BabbleParser.FnParamContext paramDecl = definition.fnParameters().fnParam(i);
            int paramRank = interpreter.scopes().lowering().numericWideningRank(paramDecl);
            if (paramRank >= 0) {
                value = Values.widenNumeric(value, paramRank);
            }
            LoweringTargets.JavaSamTarget paramSam =
                    interpreter.scopes().lowering().javaSamTarget(paramDecl);
            if (paramSam != null) {
                value = Values.toJavaSam(value, paramSam.samInterface(), paramSam.markers(), rt);
            }
            value =
                    org.babblelang.runtime.Types.check(
                            value, interpreter.scopes().lowering().checkedType(paramDecl));
            namespace.defineParameter(parameters[i], value, !ScopeAnalysis.isVar(paramDecl));
        }
        binder.rejectLeftovers();
        Object result;
        try {
            result = interpreter.evaluateIn(namespace, definition.functionBlock);
        } catch (ReturnException re) {
            return re.getValue();
        }
        if (result instanceof TailCall) {
            return result;
        }
        int blockRank =
                interpreter.scopes().lowering().numericWideningRank(definition.functionBlock);
        if (blockRank >= 0) {
            result = Values.widenNumeric(result, blockRank);
        }
        LoweringTargets.JavaSamTarget blockSam =
                interpreter.scopes().lowering().javaSamTarget(definition.functionBlock);
        if (blockSam != null) {
            result = Values.toJavaSam(result, blockSam.samInterface(), blockSam.markers(), rt);
        }
        return result;
    }

    @Override
    public int minArity() {
        return interpreter.scopes().calls().requiredParameters(definition);
    }

    @Override
    public int maxArity() {
        return interpreter.scopes().calls().restIndex(definition) >= 0
                ? Integer.MAX_VALUE
                : interpreter.scopes().calls().parameters(definition).length;
    }

    @Override
    public int restIndex() {
        return interpreter.scopes().calls().restIndex(definition);
    }

    @Override
    public boolean acceptsJavaDefaultOverload(Class<?>[] javaParams) {
        if (!BabbleFunction.super.acceptsJavaDefaultOverload(javaParams)) {
            return false;
        }
        int restIdx = restIndex();
        int limit = restIdx >= 0 ? Math.min(javaParams.length, restIdx) : javaParams.length;
        for (int i = 0; i < limit; i++) {
            String desc =
                    interpreter
                            .scopes()
                            .lowering()
                            .checkedType(definition.fnParameters().fnParam(i));
            if (!org.babblelang.runtime.Types.canAcceptJavaParamClass(desc, javaParams[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        String name = ScopeAnalysis.functionName(definition);
        return name != null ? "function " + name : "function";
    }
}
