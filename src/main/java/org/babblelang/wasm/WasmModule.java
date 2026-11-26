package org.babblelang.wasm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Self-contained WebAssembly 3.0 module IR ({@code BIP025-I0}).
 *
 * <p>Manages Wasm index spaces (types, imported + defined functions, tags, globals, passive data
 * segments, exports, and declarative function references) deterministically so repeated compiles of
 * the same Babble program yield identical {@code .wasm} bytes.
 */
public final class WasmModule {
    public enum ExportKind {
        FUNC(0x00, "func"),
        GLOBAL(0x03, "global"),
        TAG(0x04, "tag");

        private final int code;
        private final String wat;

        ExportKind(int code, String wat) {
            this.code = code;
            this.wat = wat;
        }

        public int code() {
            return code;
        }

        public String wat() {
            return wat;
        }
    }

    public record FuncImport(
            int funcIndex, String module, String field, int typeIndex, String debugName) {}

    public record Local(String name, WasmValType type) {
        public Local {
            Objects.requireNonNull(type, "type");
        }
    }

    public static final class FuncDef {
        private final int funcIndex;
        private final int typeIndex;
        private final String name;
        private final List<String> paramNames;
        private final List<Local> locals = new ArrayList<>();
        private final WasmCodeBuilder code = new WasmCodeBuilder();

        FuncDef(int funcIndex, int typeIndex, String name, List<String> paramNames) {
            this.funcIndex = funcIndex;
            this.typeIndex = typeIndex;
            this.name = name;
            this.paramNames = paramNames != null ? List.copyOf(paramNames) : List.of();
        }

        public int funcIndex() {
            return funcIndex;
        }

        public int typeIndex() {
            return typeIndex;
        }

        public String name() {
            return name;
        }

        public List<String> paramNames() {
            return paramNames;
        }

        public List<Local> locals() {
            return Collections.unmodifiableList(locals);
        }

        public int addLocal(String localName, WasmValType type, int paramCount) {
            int idx = paramCount + locals.size();
            locals.add(new Local(localName, type));
            return idx;
        }

        public WasmCodeBuilder code() {
            return code;
        }
    }

    public record TagDef(int tagIndex, String name, int typeIndex) {}

    public record GlobalDef(
            int globalIndex, String name, WasmValType type, boolean mutable, List<WasmInstr> init) {
        public GlobalDef {
            Objects.requireNonNull(type, "type");
            init = List.copyOf(init);
        }
    }

    public record ExportDef(String name, ExportKind kind, int index) {}

    private String moduleName;
    private final List<WasmTypeDef.RecGroup> recGroups = new ArrayList<>();
    private final List<WasmTypeDef.SubType> flatTypes = new ArrayList<>();
    private final List<FuncImport> funcImports = new ArrayList<>();
    private final List<FuncDef> definedFuncs = new ArrayList<>();
    private final List<TagDef> tags = new ArrayList<>();
    private final List<GlobalDef> globals = new ArrayList<>();
    private final List<ExportDef> exports = new ArrayList<>();
    private final List<byte[]> passiveDatas = new ArrayList<>();
    private final Set<Integer> declaredFuncRefs = new LinkedHashSet<>();
    private int startFuncIndex = -1;

    public WasmModule() {}

    public WasmModule(String moduleName) {
        this.moduleName = moduleName;
    }

    public String moduleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    /**
     * Reserves a type index inside a future recursive group (for mutually recursive struct/func
     * types).
     */
    public int reserveTypeIndex() {
        int idx = flatTypes.size();
        flatTypes.add(null);
        return idx;
    }

    /**
     * Commits a recursive group whose subtypes were reserved contiguously starting at the first
     * uncommitted slot.
     */
    public void commitRecGroup(List<WasmTypeDef.SubType> subtypes) {
        if (subtypes == null || subtypes.isEmpty()) {
            throw new IllegalArgumentException("Rec group must not be empty");
        }
        int expected = 0;
        while (expected < flatTypes.size() && flatTypes.get(expected) != null) {
            expected++;
        }
        if (expected + subtypes.size() > flatTypes.size()) {
            throw new IllegalArgumentException("Rec group exceeds reserved type indices");
        }
        for (WasmTypeDef.SubType sub : subtypes) {
            if (sub == null || sub.index() != expected) {
                throw new IllegalArgumentException(
                        "Rec group subtypes must fill reserved indices contiguously starting at "
                                + expected
                                + ", got : "
                                + (sub != null ? sub.index() : "null"));
            }
            if (flatTypes.get(expected) != null) {
                throw new IllegalStateException(
                        "Duplicate Wasm type definition at index : " + expected);
            }
            expected++;
        }
        for (WasmTypeDef.SubType sub : subtypes) {
            flatTypes.set(sub.index(), sub);
        }
        recGroups.add(new WasmTypeDef.RecGroup(subtypes));
    }

    public int addType(String name, boolean isFinal, int superTypeIndex, WasmTypeDef body) {
        for (int i = 0; i < flatTypes.size(); i++) {
            if (flatTypes.get(i) == null) {
                throw new IllegalStateException(
                        "Cannot addType while reserved type index " + i + " is uncommitted");
            }
        }
        int idx = flatTypes.size();
        WasmTypeDef.SubType sub = new WasmTypeDef.SubType(idx, name, isFinal, superTypeIndex, body);
        flatTypes.add(sub);
        recGroups.add(new WasmTypeDef.RecGroup(List.of(sub)));
        return idx;
    }

    public int addFuncType(String name, List<WasmValType> params, List<WasmValType> results) {
        return addType(name, true, -1, new WasmTypeDef.FuncType(params, results));
    }

    public int addStructType(
            String name, boolean isFinal, int superTypeIndex, List<WasmTypeDef.Field> fields) {
        return addType(name, isFinal, superTypeIndex, new WasmTypeDef.StructType(fields));
    }

    public int addArrayType(String name, WasmValType elementType, boolean mutable) {
        return addType(
                name,
                true,
                -1,
                new WasmTypeDef.ArrayType(new WasmTypeDef.Field("elem", elementType, mutable)));
    }

    public int addFuncImport(String module, String field, int typeIndex, String debugName) {
        if (!definedFuncs.isEmpty()) {
            throw new IllegalStateException(
                    "All function imports must be added before defined functions");
        }
        int idx = funcImports.size();
        funcImports.add(new FuncImport(idx, module, field, typeIndex, debugName));
        return idx;
    }

    public FuncDef addFunction(String name, int typeIndex) {
        return addFunction(name, typeIndex, List.of());
    }

    public FuncDef addFunction(String name, int typeIndex, List<String> paramNames) {
        int idx = funcImports.size() + definedFuncs.size();
        FuncDef fn = new FuncDef(idx, typeIndex, name, paramNames);
        definedFuncs.add(fn);
        return fn;
    }

    public int addTag(String name, int typeIndex) {
        int idx = tags.size();
        tags.add(new TagDef(idx, name, typeIndex));
        return idx;
    }

    public int addGlobal(String name, WasmValType type, boolean mutable, List<WasmInstr> init) {
        int idx = globals.size();
        globals.add(new GlobalDef(idx, name, type, mutable, init));
        return idx;
    }

    public void addExport(String name, ExportKind kind, int index) {
        exports.add(new ExportDef(name, kind, index));
    }

    public int addPassiveData(byte[] bytes) {
        int idx = passiveDatas.size();
        passiveDatas.add(bytes.clone());
        return idx;
    }

    /**
     * Marks a function index for emission in the declarative element segment (required for {@code
     * ref.func}).
     */
    public void declareFuncRef(int funcIndex) {
        declaredFuncRefs.add(funcIndex);
    }

    public void setStartFunc(int funcIndex) {
        this.startFuncIndex = funcIndex;
    }

    public List<WasmTypeDef.RecGroup> recGroups() {
        return Collections.unmodifiableList(recGroups);
    }

    public List<WasmTypeDef.SubType> flatTypes() {
        return Collections.unmodifiableList(flatTypes);
    }

    public WasmTypeDef.SubType typeAt(int index) {
        return flatTypes.get(index);
    }

    public List<FuncImport> funcImports() {
        return Collections.unmodifiableList(funcImports);
    }

    public List<FuncDef> definedFuncs() {
        return Collections.unmodifiableList(definedFuncs);
    }

    public int totalFuncCount() {
        return funcImports.size() + definedFuncs.size();
    }

    public int funcTypeIndex(int funcIndex) {
        if (funcIndex < funcImports.size()) {
            return funcImports.get(funcIndex).typeIndex();
        }
        return definedFuncs.get(funcIndex - funcImports.size()).typeIndex();
    }

    public String funcName(int funcIndex) {
        if (funcIndex < funcImports.size()) {
            FuncImport imp = funcImports.get(funcIndex);
            return imp.debugName() != null ? imp.debugName() : imp.module() + "." + imp.field();
        }
        return definedFuncs.get(funcIndex - funcImports.size()).name();
    }

    public List<TagDef> tags() {
        return Collections.unmodifiableList(tags);
    }

    public List<GlobalDef> globals() {
        return Collections.unmodifiableList(globals);
    }

    public List<ExportDef> exports() {
        return Collections.unmodifiableList(exports);
    }

    public List<byte[]> passiveDatas() {
        return Collections.unmodifiableList(passiveDatas);
    }

    public Set<Integer> declaredFuncRefs() {
        return Collections.unmodifiableSet(declaredFuncRefs);
    }

    public int startFuncIndex() {
        return startFuncIndex;
    }

    /**
     * Collects every function index referenced via {@code ref.func} in code or global initializers.
     */
    public Set<Integer> allReferencedFuncIndices() {
        Set<Integer> refs = new LinkedHashSet<>(declaredFuncRefs);
        for (GlobalDef g : globals) {
            collectFuncRefs(g.init(), refs);
        }
        for (FuncDef fn : definedFuncs) {
            collectFuncRefs(fn.code().build(), refs);
        }
        return refs;
    }

    private static void collectFuncRefs(List<WasmInstr> instrs, Set<Integer> out) {
        for (WasmInstr ins : instrs) {
            if (ins instanceof WasmInstr.Idx idx && idx.op() == WasmInstr.IdxOp.REF_FUNC) {
                out.add(idx.index());
            }
        }
    }
}
