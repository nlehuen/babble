package org.babblelang.runtime;

/**
 * Pure-Babble {@code import("...ba")} function for trimmed AOT jars that do not import Java
 * packages, avoiding any dependency on {@code JavaPackage} / {@code JavaClass} / {@code
 * JavaLinker}.
 */
public final class ModuleImportFunction implements Callable {
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 1 || !(args[0] instanceof String name)) {
            throw new BabbleException("import expects one name : a Java package, or a .ba file");
        }
        if (name.endsWith(Module.EXTENSION)) {
            if (rt.modules() == null) {
                throw new BabbleException(
                        "Cannot import " + name + " : this runtime has no modules");
            }
            return rt.modules().load(name, site != null ? site.sourceName() : null, rt);
        }
        throw new BabbleException("Cannot import Java package in trimmed AOT jar : " + name);
    }
}
