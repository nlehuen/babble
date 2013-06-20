package org.babblelang.engine;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import java.util.Collections;
import java.util.List;

public class BabbleScriptEngineFactory implements ScriptEngineFactory {
    public static final BabbleScriptEngineFactory INSTANCE = new BabbleScriptEngineFactory();

    public String getEngineName() {
        return "Babble";
    }

    public String getEngineVersion() {
        return "0.1";
    }

    public List<String> getExtensions() {
        return Collections.singletonList("ba");
    }

    public List<String> getMimeTypes() {
        return Collections.singletonList("application/x-babble");
    }

    public List<String> getNames() {
        return Collections.singletonList("babble");
    }

    public String getLanguageName() {
        return "Babble";
    }

    public String getLanguageVersion() {
        return "0.1";
    }

    public Object getParameter(String key) {
        return null;
    }

    public String getMethodCallSyntax(String obj, String m, String... args) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(obj);
        buffer.append('.');
        buffer.append(m);
        buffer.append('(');
        for (int i = 0, l = args.length; i < l; i++) {
            if (i > 0) {
                buffer.append(',');
            }
            buffer.append(args[i]);
        }
        buffer.append(')');
        return buffer.toString();
    }

    public String getOutputStatement(String toDisplay) {
        return "print(" + toDisplay + ")";
    }

    public String getProgram(String... statements) {
        StringBuilder buffer = new StringBuilder();
        for (int i = 0, l = statements.length; i < l; i++) {
            if (i > 0) {
                buffer.append('\n');
            }
            buffer.append(statements[i]);
        }
        return buffer.toString();
    }

    public ScriptEngine getScriptEngine() {
        return new BabbleScriptEngine(this);
    }
}
