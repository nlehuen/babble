package org.babblelang.wasm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-Java structural and type-index validator for {@link WasmModule} ({@code BIP025-I0},
 * {@code BIP025-D10}, enabled under {@code -Dbabble.verify=true}).
 *
 * <p>Validates WebAssembly 3.0 invariants before binary emission: recursive type group integrity,
 * WasmGC subtyping and immutability rules (catching illegal {@code struct.set}/{@code array.set} on
 * immutable fields per {@code BIP025-D11}), tag signature rules, index bounds, and structured block
 * balance.
 */
public final class WasmVerifier {
    private WasmVerifier() {
    }

    public static void verify(WasmModule module) {
        int typeCount = module.flatTypes().size();
        // Verify recGroups covers flatTypes 1:1 in exact index order 0..typeCount-1
        int expectedTypeIdx = 0;
        for (WasmTypeDef.RecGroup group : module.recGroups()) {
            if (group.types().isEmpty()) {
                throw new IllegalStateException("WasmVerifier : empty rec group");
            }
            for (WasmTypeDef.SubType st : group.types()) {
                if (st == null || st.index() != expectedTypeIdx || expectedTypeIdx >= typeCount || module.typeAt(expectedTypeIdx) != st) {
                    throw new IllegalStateException("WasmVerifier : rec group type order mismatch at index " + expectedTypeIdx);
                }
                expectedTypeIdx++;
            }
        }
        if (expectedTypeIdx != typeCount) {
            throw new IllegalStateException("WasmVerifier : rec groups cover " + expectedTypeIdx + " types, expected " + typeCount);
        }

        for (int i = 0; i < typeCount; i++) {
            WasmTypeDef.SubType st = module.typeAt(i);
            if (st == null) {
                throw new IllegalStateException("WasmVerifier : uncommitted reserved type index " + i);
            }
            if (st.index() != i) {
                throw new IllegalStateException("WasmVerifier : type index mismatch at " + i);
            }
            switch (st.body()) {
                case WasmTypeDef.FuncType ft -> {
                    for (WasmValType p : ft.params()) {
                        verifyValType(typeCount, "type " + i + " param", p);
                    }
                    for (WasmValType r : ft.results()) {
                        verifyValType(typeCount, "type " + i + " result", r);
                    }
                }
                case WasmTypeDef.StructType struct -> {
                    for (int f = 0; f < struct.fields().size(); f++) {
                        verifyStorageType(typeCount, "type " + i + " field " + f, struct.fields().get(f).type());
                    }
                }
                case WasmTypeDef.ArrayType arr -> verifyStorageType(typeCount, "type " + i + " array elem", arr.element().type());
            }
            if (st.hasSuper()) {
                if (st.superTypeIndex() < 0 || st.superTypeIndex() >= i) {
                    throw new IllegalStateException("WasmVerifier : invalid supertype " + st.superTypeIndex() + " for type " + i);
                }
                WasmTypeDef.SubType sup = module.typeAt(st.superTypeIndex());
                if (sup.isFinal()) {
                    throw new IllegalStateException("WasmVerifier : type " + i + " extends final supertype " + st.superTypeIndex());
                }
                if (sup.body().getClass() != st.body().getClass()) {
                    throw new IllegalStateException("WasmVerifier : subtype kind mismatch at type " + i);
                }
                if (sup.body() instanceof WasmTypeDef.StructType supS && st.body() instanceof WasmTypeDef.StructType subS) {
                    if (subS.fields().size() < supS.fields().size()) {
                        throw new IllegalStateException("WasmVerifier : struct subtype " + i + " has fewer fields than supertype " + st.superTypeIndex());
                    }
                    for (int f = 0; f < supS.fields().size(); f++) {
                        WasmTypeDef.Field sf = supS.fields().get(f);
                        WasmTypeDef.Field cf = subS.fields().get(f);
                        if (sf.mutable() != cf.mutable()) {
                            throw new IllegalStateException("WasmVerifier : field " + f + " mutability mismatch on struct subtype " + i);
                        }
                    }
                }
            }
        }

        // Imports
        for (WasmModule.FuncImport imp : module.funcImports()) {
            requireFuncType(module, imp.typeIndex(), "import " + imp.module() + "." + imp.field());
        }

        // Tags (must have empty result vector)
        for (WasmModule.TagDef tag : module.tags()) {
            WasmTypeDef.FuncType ft = requireFuncType(module, tag.typeIndex(), "tag " + tag.name());
            if (!ft.results().isEmpty()) {
                throw new IllegalStateException("WasmVerifier : tag " + tag.name() + " must have empty result type");
            }
        }

        // Exports (unique names & valid indices)
        Set<String> exportNames = new HashSet<>();
        for (WasmModule.ExportDef exp : module.exports()) {
            if (!exportNames.add(exp.name())) {
                throw new IllegalStateException("WasmVerifier : duplicate export name : " + exp.name());
            }
            switch (exp.kind()) {
                case FUNC -> requireBounds(exp.index(), module.totalFuncCount(), "export func " + exp.name());
                case GLOBAL -> requireBounds(exp.index(), module.globals().size(), "export global " + exp.name());
                case TAG -> requireBounds(exp.index(), module.tags().size(), "export tag " + exp.name());
            }
        }

        // Declared func refs
        for (int idx : module.declaredFuncRefs()) {
            requireBounds(idx, module.totalFuncCount(), "declared func ref");
        }

        // Start func
        if (module.startFuncIndex() >= 0) {
            requireBounds(module.startFuncIndex(), module.totalFuncCount(), "start func");
            WasmTypeDef.FuncType ft = requireFuncType(module, module.funcTypeIndex(module.startFuncIndex()), "start func");
            if (!ft.params().isEmpty() || !ft.results().isEmpty()) {
                throw new IllegalStateException("WasmVerifier : start function must have type () -> ()");
            }
        }

        // Globals (value type + constant-expression initializer)
        for (WasmModule.GlobalDef g : module.globals()) {
            verifyValType(typeCount, "global " + g.name(), g.type());
            verifyGlobalConstInit(module, g);
            verifyInstructions(module, "global " + g.name(), 0, g.init());
        }

        // Defined functions
        for (WasmModule.FuncDef fn : module.definedFuncs()) {
            WasmTypeDef.FuncType ft = requireFuncType(module, fn.typeIndex(), "func " + fn.name());
            for (int l = 0; l < fn.locals().size(); l++) {
                verifyValType(typeCount, "func " + fn.name() + " local " + l, fn.locals().get(l).type());
            }
            int totalLocals = ft.params().size() + fn.locals().size();
            verifyInstructions(module, "func " + fn.name(), totalLocals, fn.code().build());
        }
    }

    private static void verifyGlobalConstInit(WasmModule module, WasmModule.GlobalDef g) {
        String owner = "global " + g.name();
        for (WasmInstr ins : g.init()) {
            boolean allowed = switch (ins) {
                case WasmInstr.I32Const c -> true;
                case WasmInstr.I64Const c -> true;
                case WasmInstr.F32Const c -> true;
                case WasmInstr.F64Const c -> true;
                case WasmInstr.RefNull r -> true;
                case WasmInstr.Simple s -> switch (s.op()) {
                    case REF_I31, I32_ADD, I32_SUB, I32_MUL, I64_ADD, I64_SUB, I64_MUL -> true;
                    default -> false;
                };
                case WasmInstr.Idx i -> switch (i.op()) {
                    case REF_FUNC, STRUCT_NEW, STRUCT_NEW_DEFAULT, ARRAY_NEW, ARRAY_NEW_DEFAULT -> true;
                    case GLOBAL_GET -> {
                        if (i.index() < 0 || i.index() >= g.globalIndex()) {
                            throw new IllegalStateException("WasmVerifier : " + owner + " global.get must reference an earlier global, got " + i.index());
                        }
                        if (module.globals().get(i.index()).mutable()) {
                            throw new IllegalStateException("WasmVerifier : " + owner + " global.get in const init cannot reference mutable global " + i.index());
                        }
                        yield true;
                    }
                    default -> false;
                };
                case WasmInstr.Idx2 i -> i.op() == WasmInstr.Idx2Op.ARRAY_NEW_FIXED;
                default -> false;
            };
            if (!allowed) {
                throw new IllegalStateException("WasmVerifier : " + owner + " init contains non-constant instruction : " + ins);
            }
        }
    }

    private static void verifyInstructions(WasmModule module, String owner, int localCount, List<WasmInstr> instrs) {
        Deque<String> blocks = new ArrayDeque<>();
        int typeCount = module.flatTypes().size();
        int funcCount = module.totalFuncCount();
        int globalCount = module.globals().size();
        int tagCount = module.tags().size();
        int dataCount = module.passiveDatas().size();

        for (WasmInstr ins : instrs) {
            switch (ins) {
                case WasmInstr.Block b -> {
                    verifyBlockType(module, owner, b.blockType());
                    blocks.push(b.kind().wat());
                }
                case WasmInstr.TryTable t -> {
                    verifyBlockType(module, owner, t.blockType());
                    for (WasmInstr.CatchClause c : t.catches()) {
                        if (c.kind().hasTag()) {
                            requireBounds(c.tagIndex(), tagCount, owner + " try_table tag");
                        }
                        if (c.labelIndex() < 0 || c.labelIndex() > blocks.size()) {
                            throw new IllegalStateException("WasmVerifier : " + owner + " try_table label out of bounds : " + c.labelIndex());
                        }
                    }
                    blocks.push("try_table");
                }
                case WasmInstr.Simple s -> {
                    if (s.op() == WasmInstr.SimpleOp.ELSE) {
                        if (blocks.isEmpty() || !"if".equals(blocks.peek())) {
                            throw new IllegalStateException("WasmVerifier : " + owner + " else without matching if");
                        }
                        blocks.pop();
                        blocks.push("else");
                    } else if (s.op() == WasmInstr.SimpleOp.END) {
                        if (blocks.isEmpty()) {
                            throw new IllegalStateException("WasmVerifier : " + owner + " unmatched end");
                        }
                        blocks.pop();
                    }
                }
                case WasmInstr.Idx i -> {
                    switch (i.op().idxKind()) {
                        case FUNC -> requireBounds(i.index(), funcCount, owner + " " + i.op().wat());
                        case TYPE -> {
                            requireBounds(i.index(), typeCount, owner + " " + i.op().wat());
                            WasmTypeDef body = module.typeAt(i.index()).body();
                            switch (i.op()) {
                                case STRUCT_NEW, STRUCT_NEW_DEFAULT -> {
                                    if (!(body instanceof WasmTypeDef.StructType)) {
                                        throw new IllegalStateException("WasmVerifier : " + owner + " " + i.op().wat() + " expects struct type " + i.index());
                                    }
                                }
                                case ARRAY_NEW, ARRAY_NEW_DEFAULT, ARRAY_GET, ARRAY_GET_S, ARRAY_GET_U, ARRAY_SET, ARRAY_FILL -> {
                                    if (!(body instanceof WasmTypeDef.ArrayType arr)) {
                                        throw new IllegalStateException("WasmVerifier : " + owner + " " + i.op().wat() + " expects array type " + i.index());
                                    }
                                    boolean packed = arr.element().type() instanceof WasmValType.Packed;
                                    if (i.op() == WasmInstr.IdxOp.ARRAY_GET && packed) {
                                        throw new IllegalStateException("WasmVerifier : " + owner + " array.get on packed array type " + i.index() + " must use array.get_s or array.get_u");
                                    }
                                    if ((i.op() == WasmInstr.IdxOp.ARRAY_GET_S || i.op() == WasmInstr.IdxOp.ARRAY_GET_U) && !packed) {
                                        throw new IllegalStateException("WasmVerifier : " + owner + " " + i.op().wat() + " on unpacked array type " + i.index());
                                    }
                                    if ((i.op() == WasmInstr.IdxOp.ARRAY_SET || i.op() == WasmInstr.IdxOp.ARRAY_FILL) && !arr.element().mutable()) {
                                        throw new IllegalStateException("WasmVerifier : " + owner + " mutates immutable array type " + i.index());
                                    }
                                }
                                case CALL_REF, RETURN_CALL_REF -> {
                                    if (!(body instanceof WasmTypeDef.FuncType)) {
                                        throw new IllegalStateException("WasmVerifier : " + owner + " " + i.op().wat() + " expects func type " + i.index());
                                    }
                                }
                                default -> {}
                            }
                        }
                        case LOCAL -> requireBounds(i.index(), localCount, owner + " " + i.op().wat());
                        case GLOBAL -> {
                            requireBounds(i.index(), globalCount, owner + " " + i.op().wat());
                            if (i.op() == WasmInstr.IdxOp.GLOBAL_SET && !module.globals().get(i.index()).mutable()) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " sets immutable global " + i.index());
                            }
                        }
                        case TAG -> requireBounds(i.index(), tagCount, owner + " " + i.op().wat());
                        case LABEL -> {
                            if (i.index() < 0 || i.index() > blocks.size()) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " branch label out of bounds : " + i.index() + " (depth " + blocks.size() + ")");
                            }
                        }
                    }
                }
                case WasmInstr.Idx2 i -> {
                    switch (i.op()) {
                        case STRUCT_GET, STRUCT_GET_S, STRUCT_GET_U, STRUCT_SET -> {
                            requireBounds(i.index1(), typeCount, owner + " " + i.op().wat() + " type");
                            if (!(module.typeAt(i.index1()).body() instanceof WasmTypeDef.StructType st)) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " " + i.op().wat() + " expects struct type " + i.index1());
                            }
                            requireBounds(i.index2(), st.fields().size(), owner + " " + i.op().wat() + " field");
                            WasmTypeDef.Field fld = st.fields().get(i.index2());
                            boolean packed = fld.type() instanceof WasmValType.Packed;
                            if (i.op() == WasmInstr.Idx2Op.STRUCT_GET && packed) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " struct.get on packed field " + i.index2() + " of type " + i.index1() + " must use struct.get_s or struct.get_u");
                            }
                            if ((i.op() == WasmInstr.Idx2Op.STRUCT_GET_S || i.op() == WasmInstr.Idx2Op.STRUCT_GET_U) && !packed) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " " + i.op().wat() + " on unpacked field " + i.index2() + " of type " + i.index1());
                            }
                            if (i.op() == WasmInstr.Idx2Op.STRUCT_SET && !fld.mutable()) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " struct.set on immutable field " + i.index2() + " of type " + i.index1());
                            }
                        }
                        case ARRAY_NEW_FIXED -> {
                            requireBounds(i.index1(), typeCount, owner + " array.new_fixed type");
                            if (!(module.typeAt(i.index1()).body() instanceof WasmTypeDef.ArrayType)) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " array.new_fixed expects array type " + i.index1());
                            }
                            if (i.index2() < 0) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " array.new_fixed negative length : " + i.index2());
                            }
                        }
                        case ARRAY_NEW_DATA -> {
                            requireBounds(i.index1(), typeCount, owner + " array.new_data type");
                            if (!(module.typeAt(i.index1()).body() instanceof WasmTypeDef.ArrayType arr)) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " array.new_data expects array type " + i.index1());
                            }
                            if (!(arr.element().type() instanceof WasmValType.Num || arr.element().type() instanceof WasmValType.Packed)) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " array.new_data requires numeric or packed array element type at " + i.index1());
                            }
                            requireBounds(i.index2(), dataCount, owner + " array.new_data segment");
                        }
                        case ARRAY_COPY -> {
                            requireBounds(i.index1(), typeCount, owner + " array.copy dst type");
                            requireBounds(i.index2(), typeCount, owner + " array.copy src type");
                            if (!(module.typeAt(i.index1()).body() instanceof WasmTypeDef.ArrayType dstArr)) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " array.copy expects dst array type " + i.index1());
                            }
                            if (!(module.typeAt(i.index2()).body() instanceof WasmTypeDef.ArrayType)) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " array.copy expects src array type " + i.index2());
                            }
                            if (!dstArr.element().mutable()) {
                                throw new IllegalStateException("WasmVerifier : " + owner + " array.copy mutates immutable dst array type " + i.index1());
                            }
                        }
                    }
                }
                case WasmInstr.BrTable b -> {
                    for (int lbl : b.labels()) {
                        if (lbl < 0 || lbl > blocks.size()) {
                            throw new IllegalStateException("WasmVerifier : " + owner + " br_table label out of bounds : " + lbl);
                        }
                    }
                    if (b.defaultLabel() < 0 || b.defaultLabel() > blocks.size()) {
                        throw new IllegalStateException("WasmVerifier : " + owner + " br_table default label out of bounds : " + b.defaultLabel());
                    }
                }
                case WasmInstr.RefNull r -> verifyHeapType(typeCount, owner, r.heapType());
                case WasmInstr.RefTypeOp r -> verifyHeapType(typeCount, owner, r.heapType());
                case WasmInstr.BrOnCast b -> {
                    if (b.labelIndex() < 0 || b.labelIndex() > blocks.size()) {
                        throw new IllegalStateException("WasmVerifier : " + owner + " br_on_cast label out of bounds : " + b.labelIndex());
                    }
                    verifyHeapType(typeCount, owner, b.srcType());
                    verifyHeapType(typeCount, owner, b.dstType());
                }
                case WasmInstr.I32Const c -> {}
                case WasmInstr.I64Const c -> {}
                case WasmInstr.F32Const c -> {}
                case WasmInstr.F64Const c -> {}
                case WasmInstr.SelectTyped s -> verifyValType(typeCount, owner + " select", s.type());
            }
        }
        if (!blocks.isEmpty()) {
            throw new IllegalStateException("WasmVerifier : " + owner + " has " + blocks.size() + " unclosed block(s) : " + blocks);
        }
    }

    private static void verifyBlockType(WasmModule module, String owner, WasmInstr.BlockType bt) {
        if (bt instanceof WasmInstr.BlockType.TypeIdx t) {
            requireFuncType(module, t.typeIndex(), owner + " block type");
        } else if (bt instanceof WasmInstr.BlockType.Val v) {
            verifyValType(module.flatTypes().size(), owner + " block result", v.type());
        }
    }

    private static void verifyValType(int typeCount, String owner, WasmValType type) {
        if (type instanceof WasmValType.Packed p) {
            throw new IllegalStateException("WasmVerifier : " + owner + " cannot use packed storage type " + p.wat() + " as a value type");
        }
        verifyStorageType(typeCount, owner, type);
    }

    private static void verifyStorageType(int typeCount, String owner, WasmValType type) {
        if (type instanceof WasmValType.Ref r) {
            verifyHeapType(typeCount, owner, r.heapType());
        }
    }

    private static void verifyHeapType(int typeCount, String owner, WasmValType.HeapType ht) {
        if (ht instanceof WasmValType.HeapType.Indexed idx) {
            requireBounds(idx.typeIndex(), typeCount, owner + " heap type");
        }
    }

    private static WasmTypeDef.FuncType requireFuncType(WasmModule module, int typeIdx, String context) {
        requireBounds(typeIdx, module.flatTypes().size(), context + " type");
        WasmTypeDef body = module.typeAt(typeIdx).body();
        if (!(body instanceof WasmTypeDef.FuncType ft)) {
            throw new IllegalStateException("WasmVerifier : " + context + " expected func type at " + typeIdx);
        }
        return ft;
    }

    private static void requireBounds(int idx, int limit, String context) {
        if (idx < 0 || idx >= limit) {
            throw new IllegalStateException("WasmVerifier : " + context + " index " + idx + " out of bounds [0, " + limit + ")");
        }
    }
}
