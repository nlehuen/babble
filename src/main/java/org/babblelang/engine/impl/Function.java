package org.babblelang.engine.impl;

import org.babblelang.runtime.Namespace;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.runtime.Arguments;
import org.babblelang.runtime.BabbleFunction;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Frames;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;
import org.babblelang.parser.BabbleParser;

/**
 * An interpreted function : a function literal and the namespace it closes over.
 */
public class Function implements BabbleFunction {
    private final BabbleParser.FunctionLiteralContext definition;
    private final Namespace closure;
    private final Interpreter interpreter;

    Function(BabbleParser.FunctionLiteralContext definition, Namespace namespace, Interpreter interpreter) {
        this.definition = definition;
        this.closure = namespace;
        this.interpreter = interpreter;
    }

    @Override
    public Object invoke(Object thiz, ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        Namespace namespace = Frames.enter(closure, recurseTarget(thiz), thiz, interpreter.scopes().declared(definition));
        String[] parameters = interpreter.scopes().parameters(definition);
        Arguments.Binder binder = Arguments.binder(parameters, args, names);
        for (int i = 0; i < parameters.length; i++) {
            Object value = binder.take(i);
            if (value == Arguments.MISSING) {
                BabbleParser.ExpressionContext defaultValue = definition.parametersDeclaration().parameterDeclaration(i).defaultValue;
                if (defaultValue == null) {
                    throw binder.missing(i);
                }
                // A default value belongs to the function's scope, not to the call site.
                value = interpreter.evaluateDefault(namespace, defaultValue);
            }
            if (interpreter.scopes().widensToDouble(definition.parametersDeclaration().parameterDeclaration(i))) {
                value = Values.toDouble(value);
            }
            namespace.defineParameter(parameters[i], value);
        }
        binder.rejectLeftovers();
        Object result;
        try {
            result = interpreter.evaluateIn(namespace, definition.functionBlock);
        } catch (ReturnException re) {
            return re.getValue();
        }
        if (interpreter.scopes().widensToDouble(definition.functionBlock)) {
            result = Values.toDouble(result);
        }
        return result;
    }

    @Override
    public int minArity() {
        return interpreter.scopes().requiredParameters(definition);
    }

    @Override
    public int maxArity() {
        return interpreter.scopes().parameters(definition).length;
    }

    @Override
    public String toString() {
        String name = ScopeAnalysis.functionName(definition);
        return name != null ? "function " + name : "function";
    }
}
