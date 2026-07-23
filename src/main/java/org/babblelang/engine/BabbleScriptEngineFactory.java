package org.babblelang.engine;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
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
        return List.of("ba");
    }

    public List<String> getMimeTypes() {
        return List.of("application/x-babble");
    }

    public List<String> getNames() {
        return List.of("babble");
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
        return obj + '.' + m + '(' + String.join(",", args) + ')';
    }

    public String getOutputStatement(String toDisplay) {
        return "print(" + toDisplay + ")";
    }

    public String getProgram(String... statements) {
        return String.join("\n", statements);
    }

    public ScriptEngine getScriptEngine() {
        return new BabbleScriptEngine(this);
    }
}
