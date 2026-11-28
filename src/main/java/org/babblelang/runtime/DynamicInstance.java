package org.babblelang.runtime;

/**
 * Runtime contract implemented by {@link InterpretedInstance} so core runtime helpers ({@link
 * BabbleObjects}, {@link ArraySupport}, {@link Types}) do not hold direct static references to
 * interpreter-only instance classes in tree-shaken AOT jars.
 */
public interface DynamicInstance extends HasMembers {
    BabbleClass babbleClass();

    ScriptRuntime runtime();

    Object methodOrOverload(String name);

    Object initMember(int index, Object value);

    Object face();
}
