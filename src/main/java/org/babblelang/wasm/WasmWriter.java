package org.babblelang.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure-Java WebAssembly 3.0 binary encoder ({@code BIP025-I0}, {@code BIP025-D2}).
 *
 * <p>Encodes {@link WasmModule} into spec-compliant {@code .wasm} bytes (WasmGC {@code rec}/{@code sub},
 * typed function references, {@code try_table}/{@code exnref} tags, tail calls, passive data segments,
 * and optional {@code name} custom debug section) with zero external dependencies.
 */
public final class WasmWriter {
    private static final byte[] MAGIC_AND_VERSION = {
            0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00
    };

    private WasmWriter() {
    }

    public static byte[] encode(WasmModule module) {
        return encode(module, Boolean.getBoolean("babble.wasmDebugNames"));
    }

    public static byte[] encode(WasmModule module, boolean includeDebugNames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        out.write(MAGIC_AND_VERSION, 0, MAGIC_AND_VERSION.length);

        // Wasm 3.0 strict section order:
        // 1 Type, 2 Import, 3 Function, 13 Tag, 6 Global, 7 Export, 8 Start, 9 Element, 12 DataCount, 10 Code, 11 Data, 0 Custom(name)
        if (!module.recGroups().isEmpty()) {
            writeSection(out, 1, encodeTypeSection(module));
        }
        if (!module.funcImports().isEmpty()) {
            writeSection(out, 2, encodeImportSection(module));
        }
        if (!module.definedFuncs().isEmpty()) {
            writeSection(out, 3, encodeFunctionSection(module));
        }
        if (!module.tags().isEmpty()) {
            writeSection(out, 13, encodeTagSection(module));
        }
        if (!module.globals().isEmpty()) {
            writeSection(out, 6, encodeGlobalSection(module));
        }
        if (!module.exports().isEmpty()) {
            writeSection(out, 7, encodeExportSection(module));
        }
        if (module.startFuncIndex() >= 0) {
            ByteArrayOutputStream sec = new ByteArrayOutputStream();
            WasmLeb128.writeU32(sec, module.startFuncIndex());
            writeSection(out, 8, sec.toByteArray());
        }
        Set<Integer> funcRefs = module.allReferencedFuncIndices();
        if (!funcRefs.isEmpty()) {
            writeSection(out, 9, encodeDeclarativeElemSection(funcRefs));
        }
        if (!module.passiveDatas().isEmpty()) {
            ByteArrayOutputStream sec = new ByteArrayOutputStream();
            WasmLeb128.writeU32(sec, module.passiveDatas().size());
            writeSection(out, 12, sec.toByteArray());
        }
        if (!module.definedFuncs().isEmpty()) {
            writeSection(out, 10, encodeCodeSection(module));
        }
        if (!module.passiveDatas().isEmpty()) {
            writeSection(out, 11, encodeDataSection(module));
        }
        if (includeDebugNames) {
            byte[] nameSec = encodeNameSection(module);
            if (nameSec.length > 0) {
                writeSection(out, 0, nameSec);
            }
        }
        return out.toByteArray();
    }

    private static void writeSection(ByteArrayOutputStream out, int id, byte[] payload) {
        out.write(id);
        WasmLeb128.writeU32(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    private static byte[] encodeTypeSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        List<WasmTypeDef.RecGroup> groups = module.recGroups();
        WasmLeb128.writeU32(sec, groups.size());
        for (WasmTypeDef.RecGroup group : groups) {
            if (group.types().size() == 1) {
                encodeSubType(sec, group.types().get(0));
            } else {
                sec.write(0x4E); // rec
                WasmLeb128.writeU32(sec, group.types().size());
                for (WasmTypeDef.SubType sub : group.types()) {
                    encodeSubType(sec, sub);
                }
            }
        }
        return sec.toByteArray();
    }

    private static void encodeSubType(ByteArrayOutputStream out, WasmTypeDef.SubType sub) {
        if (!sub.isFinal() || sub.hasSuper()) {
            out.write(sub.isFinal() ? 0x4F : 0x50);
            if (sub.hasSuper()) {
                WasmLeb128.writeU32(out, 1);
                WasmLeb128.writeU32(out, sub.superTypeIndex());
            } else {
                WasmLeb128.writeU32(out, 0);
            }
        }
        encodeCompType(out, sub.body());
    }

    private static void encodeCompType(ByteArrayOutputStream out, WasmTypeDef body) {
        switch (body) {
            case WasmTypeDef.FuncType f -> {
                out.write(0x60);
                WasmLeb128.writeU32(out, f.params().size());
                for (WasmValType p : f.params()) {
                    p.writeTo(out);
                }
                WasmLeb128.writeU32(out, f.results().size());
                for (WasmValType r : f.results()) {
                    r.writeTo(out);
                }
            }
            case WasmTypeDef.StructType s -> {
                out.write(0x5F);
                WasmLeb128.writeU32(out, s.fields().size());
                for (WasmTypeDef.Field f : s.fields()) {
                    f.type().writeTo(out);
                    out.write(f.mutable() ? 0x01 : 0x00);
                }
            }
            case WasmTypeDef.ArrayType a -> {
                out.write(0x5E);
                a.element().type().writeTo(out);
                out.write(a.element().mutable() ? 0x01 : 0x00);
            }
        }
    }

    private static byte[] encodeImportSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        List<WasmModule.FuncImport> imports = module.funcImports();
        WasmLeb128.writeU32(sec, imports.size());
        for (WasmModule.FuncImport imp : imports) {
            WasmLeb128.writeName(sec, imp.module());
            WasmLeb128.writeName(sec, imp.field());
            sec.write(0x00); // func import
            WasmLeb128.writeU32(sec, imp.typeIndex());
        }
        return sec.toByteArray();
    }

    private static byte[] encodeFunctionSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        List<WasmModule.FuncDef> funcs = module.definedFuncs();
        WasmLeb128.writeU32(sec, funcs.size());
        for (WasmModule.FuncDef fn : funcs) {
            WasmLeb128.writeU32(sec, fn.typeIndex());
        }
        return sec.toByteArray();
    }

    private static byte[] encodeTagSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        List<WasmModule.TagDef> tags = module.tags();
        WasmLeb128.writeU32(sec, tags.size());
        for (WasmModule.TagDef tag : tags) {
            sec.write(0x00); // exception attribute
            WasmLeb128.writeU32(sec, tag.typeIndex());
        }
        return sec.toByteArray();
    }

    private static byte[] encodeGlobalSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        List<WasmModule.GlobalDef> globals = module.globals();
        WasmLeb128.writeU32(sec, globals.size());
        for (WasmModule.GlobalDef g : globals) {
            g.type().writeTo(sec);
            sec.write(g.mutable() ? 0x01 : 0x00);
            encodeInstructions(sec, g.init());
            sec.write(0x0B); // end expr
        }
        return sec.toByteArray();
    }

    private static byte[] encodeExportSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        List<WasmModule.ExportDef> exports = module.exports();
        WasmLeb128.writeU32(sec, exports.size());
        for (WasmModule.ExportDef exp : exports) {
            WasmLeb128.writeName(sec, exp.name());
            sec.write(exp.kind().code());
            WasmLeb128.writeU32(sec, exp.index());
        }
        return sec.toByteArray();
    }

    private static byte[] encodeDeclarativeElemSection(Set<Integer> funcRefs) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        WasmLeb128.writeU32(sec, 1); // 1 declarative segment
        sec.write(0x03); // flags = declarative, funcidx vector
        sec.write(0x00); // elemkind = funcref
        WasmLeb128.writeU32(sec, funcRefs.size());
        for (int idx : funcRefs) {
            WasmLeb128.writeU32(sec, idx);
        }
        return sec.toByteArray();
    }

    private static byte[] encodeCodeSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        List<WasmModule.FuncDef> funcs = module.definedFuncs();
        WasmLeb128.writeU32(sec, funcs.size());
        for (WasmModule.FuncDef fn : funcs) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            encodeLocals(body, fn.locals());
            encodeInstructions(body, fn.code().build());
            body.write(0x0B); // end func
            byte[] bytes = body.toByteArray();
            WasmLeb128.writeU32(sec, bytes.length);
            sec.write(bytes, 0, bytes.length);
        }
        return sec.toByteArray();
    }

    private static void encodeLocals(ByteArrayOutputStream out, List<WasmModule.Local> locals) {
        if (locals.isEmpty()) {
            WasmLeb128.writeU32(out, 0);
            return;
        }
        // Run-length encode adjacent locals of the same type
        record Run(int count, WasmValType type) {}
        List<Run> runs = new ArrayList<>();
        WasmValType curType = locals.get(0).type();
        int curCount = 1;
        for (int i = 1; i < locals.size(); i++) {
            WasmValType t = locals.get(i).type();
            if (t.equals(curType)) {
                curCount++;
            } else {
                runs.add(new Run(curCount, curType));
                curType = t;
                curCount = 1;
            }
        }
        runs.add(new Run(curCount, curType));
        WasmLeb128.writeU32(out, runs.size());
        for (Run run : runs) {
            WasmLeb128.writeU32(out, run.count());
            run.type().writeTo(out);
        }
    }

    private static byte[] encodeDataSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        List<byte[]> datas = module.passiveDatas();
        WasmLeb128.writeU32(sec, datas.size());
        for (byte[] data : datas) {
            sec.write(0x01); // passive segment
            WasmLeb128.writeU32(sec, data.length);
            sec.write(data, 0, data.length);
        }
        return sec.toByteArray();
    }

    private static byte[] encodeNameSection(WasmModule module) {
        ByteArrayOutputStream sec = new ByteArrayOutputStream();
        WasmLeb128.writeName(sec, "name");
        int headerLen = sec.size();
        if (module.moduleName() != null && !module.moduleName().isEmpty()) {
            ByteArrayOutputStream sub = new ByteArrayOutputStream();
            WasmLeb128.writeName(sub, module.moduleName());
            writeSubSection(sec, 0, sub.toByteArray());
        }
        // Subsection 1: function names
        ByteArrayOutputStream fnSub = new ByteArrayOutputStream();
        int totalFuncs = module.totalFuncCount();
        List<Integer> namedFuncs = new ArrayList<>();
        for (int i = 0; i < totalFuncs; i++) {
            String n = module.funcName(i);
            if (n != null && !n.isEmpty()) {
                namedFuncs.add(i);
            }
        }
        if (!namedFuncs.isEmpty()) {
            WasmLeb128.writeU32(fnSub, namedFuncs.size());
            for (int idx : namedFuncs) {
                WasmLeb128.writeU32(fnSub, idx);
                WasmLeb128.writeName(fnSub, module.funcName(idx));
            }
            writeSubSection(sec, 1, fnSub.toByteArray());
        }
        // Subsection 2: local names (indirect name map: funcIdx -> localIdx -> name)
        List<WasmModule.FuncDef> funcsWithNamedLocals = new ArrayList<>();
        List<List<int[]>> localIndicesPerFunc = new ArrayList<>();
        List<List<String>> localNamesPerFunc = new ArrayList<>();
        for (WasmModule.FuncDef fn : module.definedFuncs()) {
            List<int[]> idxs = new ArrayList<>();
            List<String> names = new ArrayList<>();
            int paramCount = ((WasmTypeDef.FuncType) module.flatTypes().get(fn.typeIndex()).body()).params().size();
            List<String> pNames = fn.paramNames();
            int pLimit = Math.min(pNames.size(), paramCount);
            for (int p = 0; p < pLimit; p++) {
                String pn = pNames.get(p);
                if (pn != null && !pn.isEmpty()) {
                    idxs.add(new int[]{p});
                    names.add(pn);
                }
            }
            List<WasmModule.Local> locals = fn.locals();
            for (int l = 0; l < locals.size(); l++) {
                String ln = locals.get(l).name();
                if (ln != null && !ln.isEmpty()) {
                    idxs.add(new int[]{paramCount + l});
                    names.add(ln);
                }
            }
            if (!idxs.isEmpty()) {
                funcsWithNamedLocals.add(fn);
                localIndicesPerFunc.add(idxs);
                localNamesPerFunc.add(names);
            }
        }
        if (!funcsWithNamedLocals.isEmpty()) {
            ByteArrayOutputStream lSub = new ByteArrayOutputStream();
            WasmLeb128.writeU32(lSub, funcsWithNamedLocals.size());
            for (int i = 0; i < funcsWithNamedLocals.size(); i++) {
                WasmLeb128.writeU32(lSub, funcsWithNamedLocals.get(i).funcIndex());
                List<int[]> idxs = localIndicesPerFunc.get(i);
                List<String> names = localNamesPerFunc.get(i);
                WasmLeb128.writeU32(lSub, idxs.size());
                for (int j = 0; j < idxs.size(); j++) {
                    WasmLeb128.writeU32(lSub, idxs.get(j)[0]);
                    WasmLeb128.writeName(lSub, names.get(j));
                }
            }
            writeSubSection(sec, 2, lSub.toByteArray());
        }
        // Subsection 4: type names
        List<WasmTypeDef.SubType> namedTypes = new ArrayList<>();
        for (WasmTypeDef.SubType st : module.flatTypes()) {
            if (st != null && st.name() != null && !st.name().isEmpty()) {
                namedTypes.add(st);
            }
        }
        if (!namedTypes.isEmpty()) {
            ByteArrayOutputStream tSub = new ByteArrayOutputStream();
            WasmLeb128.writeU32(tSub, namedTypes.size());
            for (WasmTypeDef.SubType st : namedTypes) {
                WasmLeb128.writeU32(tSub, st.index());
                WasmLeb128.writeName(tSub, st.name());
            }
            writeSubSection(sec, 4, tSub.toByteArray());
        }
        // Subsection 7: global names
        List<WasmModule.GlobalDef> namedGlobals = new ArrayList<>();
        for (WasmModule.GlobalDef g : module.globals()) {
            if (g.name() != null && !g.name().isEmpty()) {
                namedGlobals.add(g);
            }
        }
        if (!namedGlobals.isEmpty()) {
            ByteArrayOutputStream gSub = new ByteArrayOutputStream();
            WasmLeb128.writeU32(gSub, namedGlobals.size());
            for (WasmModule.GlobalDef g : namedGlobals) {
                WasmLeb128.writeU32(gSub, g.globalIndex());
                WasmLeb128.writeName(gSub, g.name());
            }
            writeSubSection(sec, 7, gSub.toByteArray());
        }
        // Subsection 10: struct field names (indirect name map: typeIdx -> fieldIdx -> name)
        List<WasmTypeDef.SubType> structsWithNamedFields = new ArrayList<>();
        List<List<int[]>> fieldIndicesPerType = new ArrayList<>();
        List<List<String>> fieldNamesPerType = new ArrayList<>();
        for (WasmTypeDef.SubType st : module.flatTypes()) {
            if (st != null && st.body() instanceof WasmTypeDef.StructType struct) {
                List<int[]> idxs = new ArrayList<>();
                List<String> names = new ArrayList<>();
                List<WasmTypeDef.Field> fields = struct.fields();
                for (int f = 0; f < fields.size(); f++) {
                    String fn = fields.get(f).name();
                    if (fn != null && !fn.isEmpty()) {
                        idxs.add(new int[]{f});
                        names.add(fn);
                    }
                }
                if (!idxs.isEmpty()) {
                    structsWithNamedFields.add(st);
                    fieldIndicesPerType.add(idxs);
                    fieldNamesPerType.add(names);
                }
            }
        }
        if (!structsWithNamedFields.isEmpty()) {
            ByteArrayOutputStream fSub = new ByteArrayOutputStream();
            WasmLeb128.writeU32(fSub, structsWithNamedFields.size());
            for (int i = 0; i < structsWithNamedFields.size(); i++) {
                WasmLeb128.writeU32(fSub, structsWithNamedFields.get(i).index());
                List<int[]> idxs = fieldIndicesPerType.get(i);
                List<String> names = fieldNamesPerType.get(i);
                WasmLeb128.writeU32(fSub, idxs.size());
                for (int j = 0; j < idxs.size(); j++) {
                    WasmLeb128.writeU32(fSub, idxs.get(j)[0]);
                    WasmLeb128.writeName(fSub, names.get(j));
                }
            }
            writeSubSection(sec, 10, fSub.toByteArray());
        }
        if (sec.size() == headerLen) {
            return new byte[0];
        }
        return sec.toByteArray();
    }

    private static void writeSubSection(ByteArrayOutputStream out, int id, byte[] payload) {
        out.write(id);
        WasmLeb128.writeU32(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    private static void encodeBlockType(ByteArrayOutputStream out, WasmInstr.BlockType bt) {
        switch (bt) {
            case WasmInstr.BlockType.Empty e -> out.write(0x40);
            case WasmInstr.BlockType.Val v -> v.type().writeTo(out);
            case WasmInstr.BlockType.TypeIdx t -> WasmLeb128.writeS33(out, t.typeIndex());
        }
    }

    private static void encodeInstructions(ByteArrayOutputStream out, List<WasmInstr> instrs) {
        for (WasmInstr ins : instrs) {
            switch (ins) {
                case WasmInstr.Simple s -> {
                    if (s.op().prefix() != 0) {
                        out.write(s.op().prefix());
                        WasmLeb128.writeU32(out, s.op().opcode());
                    } else {
                        out.write(s.op().opcode());
                    }
                }
                case WasmInstr.I32Const c -> {
                    out.write(0x41);
                    WasmLeb128.writeS32(out, c.value());
                }
                case WasmInstr.I64Const c -> {
                    out.write(0x42);
                    WasmLeb128.writeS64(out, c.value());
                }
                case WasmInstr.F32Const c -> {
                    out.write(0x43);
                    WasmLeb128.writeF32(out, c.value());
                }
                case WasmInstr.F64Const c -> {
                    out.write(0x44);
                    WasmLeb128.writeF64(out, c.value());
                }
                case WasmInstr.Idx i -> {
                    if (i.op().prefix() != 0) {
                        out.write(i.op().prefix());
                        WasmLeb128.writeU32(out, i.op().opcode());
                    } else {
                        out.write(i.op().opcode());
                    }
                    WasmLeb128.writeU32(out, i.index());
                }
                case WasmInstr.Idx2 i -> {
                    out.write(i.op().prefix());
                    WasmLeb128.writeU32(out, i.op().opcode());
                    WasmLeb128.writeU32(out, i.index1());
                    WasmLeb128.writeU32(out, i.index2());
                }
                case WasmInstr.Block b -> {
                    out.write(b.kind().opcode());
                    encodeBlockType(out, b.blockType());
                }
                case WasmInstr.TryTable t -> {
                    out.write(0x1F);
                    encodeBlockType(out, t.blockType());
                    WasmLeb128.writeU32(out, t.catches().size());
                    for (WasmInstr.CatchClause c : t.catches()) {
                        out.write(c.kind().opcode());
                        if (c.kind().hasTag()) {
                            WasmLeb128.writeU32(out, c.tagIndex());
                        }
                        WasmLeb128.writeU32(out, c.labelIndex());
                    }
                }
                case WasmInstr.BrTable b -> {
                    out.write(0x0E);
                    WasmLeb128.writeU32(out, b.labels().size());
                    for (int lbl : b.labels()) {
                        WasmLeb128.writeU32(out, lbl);
                    }
                    WasmLeb128.writeU32(out, b.defaultLabel());
                }
                case WasmInstr.RefNull r -> {
                    out.write(0xD0);
                    r.heapType().writeTo(out);
                }
                case WasmInstr.RefTypeOp r -> {
                    out.write(0xFB);
                    WasmLeb128.writeU32(out, r.kind().subOpcode());
                    r.heapType().writeTo(out);
                }
                case WasmInstr.BrOnCast b -> {
                    out.write(0xFB);
                    WasmLeb128.writeU32(out, b.fail() ? 0x19 : 0x18);
                    int flags = (b.srcNullable() ? 1 : 0) | (b.dstNullable() ? 2 : 0);
                    out.write(flags);
                    WasmLeb128.writeU32(out, b.labelIndex());
                    b.srcType().writeTo(out);
                    b.dstType().writeTo(out);
                }
                case WasmInstr.SelectTyped s -> {
                    out.write(0x1C);
                    WasmLeb128.writeU32(out, 1);
                    s.type().writeTo(out);
                }
            }
        }
    }
}
