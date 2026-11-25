package org.babblelang.wasm;

import java.util.List;
import java.util.Set;

/**
 * Deterministic human-readable WebAssembly Text ({@code .wat}) printer for {@link WasmModule}
 * ({@code BIP025-P0}, {@code BIP025-D2}, {@code BIP029} {@code wat} blocks).
 */
public final class WatPrinter {
    private WatPrinter() {
    }

    public static String print(WasmModule module) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("(module");
        if (module.moduleName() != null && !module.moduleName().isEmpty()) {
            sb.append(" $").append(sanitize(module.moduleName()));
        }
        sb.append('\n');

        // Types / rec groups
        for (WasmTypeDef.RecGroup group : module.recGroups()) {
            if (group.types().size() == 1) {
                sb.append("  ");
                printSubType(sb, module, group.types().get(0));
                sb.append('\n');
            } else {
                sb.append("  (rec\n");
                for (WasmTypeDef.SubType sub : group.types()) {
                    sb.append("    ");
                    printSubType(sb, module, sub);
                    sb.append('\n');
                }
                sb.append("  )\n");
            }
        }

        // Imports
        for (WasmModule.FuncImport imp : module.funcImports()) {
            sb.append("  (import \"").append(escape(imp.module())).append("\" \"")
                    .append(escape(imp.field())).append("\" (func ").append(funcLabel(module, imp.funcIndex()))
                    .append(" (type ").append(typeLabel(module, imp.typeIndex())).append(")))\n");
        }

        // Tags
        for (WasmModule.TagDef tag : module.tags()) {
            sb.append("  (tag ").append(tagLabel(module, tag.tagIndex()))
                    .append(" (type ").append(typeLabel(module, tag.typeIndex())).append("))\n");
        }

        // Globals
        for (WasmModule.GlobalDef g : module.globals()) {
            sb.append("  (global ").append(globalLabel(module, g.globalIndex())).append(' ');
            if (g.mutable()) {
                sb.append("(mut ").append(formatType(module, g.type())).append(')');
            } else {
                sb.append(formatType(module, g.type()));
            }
            sb.append(' ');
            printInlineInstrs(sb, module, g.init());
            sb.append(")\n");
        }

        // Exports
        for (WasmModule.ExportDef exp : module.exports()) {
            sb.append("  (export \"").append(escape(exp.name())).append("\" (")
                    .append(exp.kind().wat()).append(' ');
            switch (exp.kind()) {
                case FUNC -> sb.append(funcLabel(module, exp.index()));
                case GLOBAL -> sb.append(globalLabel(module, exp.index()));
                case TAG -> sb.append(tagLabel(module, exp.index()));
            }
            sb.append("))\n");
        }

        // Start
        if (module.startFuncIndex() >= 0) {
            sb.append("  (start ").append(funcLabel(module, module.startFuncIndex())).append(")\n");
        }

        // Declarative func refs
        Set<Integer> funcRefs = module.allReferencedFuncIndices();
        if (!funcRefs.isEmpty()) {
            sb.append("  (elem declare func");
            for (int idx : funcRefs) {
                sb.append(' ').append(funcLabel(module, idx));
            }
            sb.append(")\n");
        }

        // Passive data segments
        for (int i = 0; i < module.passiveDatas().size(); i++) {
            sb.append("  (data $d").append(i).append(" \"").append(escapeBytes(module.passiveDatas().get(i))).append("\")\n");
        }

        // Defined functions
        for (WasmModule.FuncDef fn : module.definedFuncs()) {
            printFunc(sb, module, fn);
        }

        sb.append(")\n");
        return sb.toString();
    }

    private static void printSubType(StringBuilder sb, WasmModule module, WasmTypeDef.SubType sub) {
        sb.append("(type ").append(typeLabel(module, sub.index())).append(' ');
        boolean wrapSub = !sub.isFinal() || sub.hasSuper();
        if (wrapSub) {
            sb.append(sub.isFinal() ? "(sub final" : "(sub");
            if (sub.hasSuper()) {
                sb.append(' ').append(typeLabel(module, sub.superTypeIndex()));
            }
            sb.append(' ');
        }
        switch (sub.body()) {
            case WasmTypeDef.FuncType f -> {
                sb.append("(func");
                for (WasmValType p : f.params()) {
                    sb.append(" (param ").append(formatType(module, p)).append(')');
                }
                for (WasmValType r : f.results()) {
                    sb.append(" (result ").append(formatType(module, r)).append(')');
                }
                sb.append(')');
            }
            case WasmTypeDef.StructType s -> {
                sb.append("(struct");
                for (WasmTypeDef.Field f : s.fields()) {
                    sb.append(" (field");
                    if (f.name() != null && !f.name().isEmpty()) {
                        sb.append(" $").append(sanitize(f.name()));
                    }
                    sb.append(' ');
                    if (f.mutable()) {
                        sb.append("(mut ").append(formatType(module, f.type())).append(')');
                    } else {
                        sb.append(formatType(module, f.type()));
                    }
                    sb.append(')');
                }
                sb.append(')');
            }
            case WasmTypeDef.ArrayType a -> {
                sb.append("(array ");
                if (a.element().mutable()) {
                    sb.append("(mut ").append(formatType(module, a.element().type())).append(')');
                } else {
                    sb.append(formatType(module, a.element().type()));
                }
                sb.append(')');
            }
        }
        if (wrapSub) {
            sb.append(')');
        }
        sb.append(')');
    }

    private static void printFunc(StringBuilder sb, WasmModule module, WasmModule.FuncDef fn) {
        sb.append("  (func ").append(funcLabel(module, fn.funcIndex()))
                .append(" (type ").append(typeLabel(module, fn.typeIndex())).append(')');
        WasmTypeDef.FuncType ft = (WasmTypeDef.FuncType) module.typeAt(fn.typeIndex()).body();
        for (int i = 0; i < ft.params().size(); i++) {
            sb.append(" (param");
            if (i < fn.paramNames().size() && fn.paramNames().get(i) != null) {
                sb.append(" $").append(sanitize(fn.paramNames().get(i)));
            }
            sb.append(' ').append(formatType(module, ft.params().get(i))).append(')');
        }
        for (WasmValType r : ft.results()) {
            sb.append(" (result ").append(formatType(module, r)).append(')');
        }
        sb.append('\n');
        for (WasmModule.Local loc : fn.locals()) {
            sb.append("    (local");
            if (loc.name() != null && !loc.name().isEmpty()) {
                sb.append(" $").append(sanitize(loc.name()));
            }
            sb.append(' ').append(formatType(module, loc.type())).append(")\n");
        }
        int indent = 2;
        for (WasmInstr ins : fn.code().build()) {
            if (ins instanceof WasmInstr.Simple s
                    && (s.op() == WasmInstr.SimpleOp.END || s.op() == WasmInstr.SimpleOp.ELSE)) {
                indent = Math.max(2, indent - 1);
            }
            sb.append("  ".repeat(indent));
            printInstr(sb, module, ins);
            sb.append('\n');
            if (ins instanceof WasmInstr.Block || ins instanceof WasmInstr.TryTable) {
                indent++;
            } else if (ins instanceof WasmInstr.Simple s && s.op() == WasmInstr.SimpleOp.ELSE) {
                indent++;
            }
        }
        sb.append("  )\n");
    }

    private static void printInlineInstrs(StringBuilder sb, WasmModule module, List<WasmInstr> instrs) {
        for (int i = 0; i < instrs.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append('(');
            printInstr(sb, module, instrs.get(i));
            sb.append(')');
        }
    }

    private static void printInstr(StringBuilder sb, WasmModule module, WasmInstr ins) {
        switch (ins) {
            case WasmInstr.Simple s -> sb.append(s.op().wat());
            case WasmInstr.I32Const c -> sb.append("i32.const ").append(c.value());
            case WasmInstr.I64Const c -> sb.append("i64.const ").append(c.value());
            case WasmInstr.F32Const c -> sb.append("f32.const ").append(c.value());
            case WasmInstr.F64Const c -> sb.append("f64.const ").append(c.value());
            case WasmInstr.Idx i -> {
                sb.append(i.op().wat()).append(' ');
                switch (i.op().idxKind()) {
                    case FUNC -> sb.append(funcLabel(module, i.index()));
                    case TYPE -> sb.append(typeLabel(module, i.index()));
                    case GLOBAL -> sb.append(globalLabel(module, i.index()));
                    case TAG -> sb.append(tagLabel(module, i.index()));
                    case LOCAL, LABEL -> sb.append(i.index());
                }
            }
            case WasmInstr.Idx2 i -> {
                sb.append(i.op().wat()).append(' ');
                switch (i.op()) {
                    case STRUCT_GET, STRUCT_GET_S, STRUCT_GET_U, STRUCT_SET ->
                            sb.append(typeLabel(module, i.index1())).append(' ').append(i.index2());
                    case ARRAY_NEW_FIXED ->
                            sb.append(typeLabel(module, i.index1())).append(' ').append(i.index2());
                    case ARRAY_NEW_DATA ->
                            sb.append(typeLabel(module, i.index1())).append(" $d").append(i.index2());
                    case ARRAY_COPY ->
                            sb.append(typeLabel(module, i.index1())).append(' ').append(typeLabel(module, i.index2()));
                }
            }
            case WasmInstr.Block b -> {
                sb.append(b.kind().wat());
                printBlockType(sb, module, b.blockType());
            }
            case WasmInstr.TryTable t -> {
                sb.append("try_table");
                printBlockType(sb, module, t.blockType());
                for (WasmInstr.CatchClause c : t.catches()) {
                    sb.append(" (").append(c.kind().wat());
                    if (c.kind().hasTag()) {
                        sb.append(' ').append(tagLabel(module, c.tagIndex()));
                    }
                    sb.append(' ').append(c.labelIndex()).append(')');
                }
            }
            case WasmInstr.BrTable b -> {
                sb.append("br_table");
                for (int lbl : b.labels()) {
                    sb.append(' ').append(lbl);
                }
                sb.append(' ').append(b.defaultLabel());
            }
            case WasmInstr.RefNull r -> sb.append("ref.null ").append(formatHeapType(module, r.heapType()));
            case WasmInstr.RefTypeOp r -> sb.append(r.kind().wat()).append(" (ref ")
                    .append(r.kind().nullable() ? "null " : "")
                    .append(formatHeapType(module, r.heapType())).append(')');
            case WasmInstr.BrOnCast b -> sb.append(b.fail() ? "br_on_cast_fail " : "br_on_cast ")
                    .append(b.labelIndex())
                    .append(" (ref ").append(b.srcNullable() ? "null " : "").append(formatHeapType(module, b.srcType())).append(')')
                    .append(" (ref ").append(b.dstNullable() ? "null " : "").append(formatHeapType(module, b.dstType())).append(')');
            case WasmInstr.SelectTyped s -> sb.append("select (result ").append(formatType(module, s.type())).append(')');
        }
    }

    private static void printBlockType(StringBuilder sb, WasmModule module, WasmInstr.BlockType bt) {
        switch (bt) {
            case WasmInstr.BlockType.Empty e -> {}
            case WasmInstr.BlockType.Val v -> sb.append(" (result ").append(formatType(module, v.type())).append(')');
            case WasmInstr.BlockType.TypeIdx t -> sb.append(" (type ").append(typeLabel(module, t.typeIndex())).append(')');
        }
    }

    private static String formatType(WasmModule module, WasmValType type) {
        if (type instanceof WasmValType.Ref r) {
            if (r.nullable() && r.heapType() instanceof WasmValType.HeapType.Abstract) {
                return r.wat();
            }
            String ht = formatHeapType(module, r.heapType());
            return r.nullable() ? "(ref null " + ht + ")" : "(ref " + ht + ")";
        }
        return type.wat();
    }

    private static String formatHeapType(WasmModule module, WasmValType.HeapType ht) {
        if (ht instanceof WasmValType.HeapType.Indexed idx) {
            return typeLabel(module, idx.typeIndex());
        }
        return ht.wat();
    }

    private static String typeLabel(WasmModule module, int idx) {
        if (idx >= 0 && idx < module.flatTypes().size()) {
            WasmTypeDef.SubType st = module.typeAt(idx);
            if (st != null && st.name() != null && !st.name().isEmpty()) {
                return "$" + sanitize(st.name());
            }
        }
        return "$t" + idx;
    }

    private static String funcLabel(WasmModule module, int idx) {
        if (idx >= 0 && idx < module.totalFuncCount()) {
            String name = module.funcName(idx);
            if (name != null && !name.isEmpty()) {
                return "$" + sanitize(name);
            }
        }
        return "$f" + idx;
    }

    private static String globalLabel(WasmModule module, int idx) {
        if (idx >= 0 && idx < module.globals().size()) {
            String name = module.globals().get(idx).name();
            if (name != null && !name.isEmpty()) {
                return "$" + sanitize(name);
            }
        }
        return "$g" + idx;
    }

    private static String tagLabel(WasmModule module, int idx) {
        if (idx >= 0 && idx < module.tags().size()) {
            String name = module.tags().get(idx).name();
            if (name != null && !name.isEmpty()) {
                return "$" + sanitize(name);
            }
        }
        return "$tag" + idx;
    }

    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeBytes(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int u = b & 0xFF;
            if (u >= 0x20 && u <= 0x7E && u != '"' && u != '\\') {
                sb.append((char) u);
            } else {
                sb.append(String.format("\\%02x", u));
            }
        }
        return sb.toString();
    }

    /** Wraps {@code module} as an {@link org.babblelang.ir.IrDumpable} for {@link org.babblelang.ir.IrTier#WAT}. */
    public static org.babblelang.ir.IrDumpable asDumpable(WasmModule module) {
        return new org.babblelang.ir.IrDumpable() {
            @Override
            public org.babblelang.ir.IrTier tier() {
                return org.babblelang.ir.IrTier.WAT;
            }

            @Override
            public String printRaw() {
                return print(module);
            }

            @Override
            public String printCanonical(org.babblelang.ir.IrTarget target) {
                if (target instanceof org.babblelang.ir.IrTarget.Module) {
                    return print(module).strip();
                }
                StringBuilder sb = new StringBuilder(512);
                if (target instanceof org.babblelang.ir.IrTarget.TopLevel) {
                    for (WasmModule.FuncDef fn : module.definedFuncs()) {
                        if ("M0_init".equals(fn.name())) {
                            printFunc(sb, module, fn);
                            return sb.toString().strip();
                        }
                    }
                    throw new IllegalArgumentException("No top-level M0_init function in WAT module");
                }
                if (target instanceof org.babblelang.ir.IrTarget.Fn f) {
                    String suffix = "_" + sanitize(f.name());
                    for (WasmModule.FuncDef fn : module.definedFuncs()) {
                        String n = fn.name();
                        if (n != null && !n.endsWith("_generic") && !n.contains("$")
                                && (n.equals(f.name()) || n.endsWith(suffix))) {
                            printFunc(sb, module, fn);
                        }
                    }
                    if (!sb.isEmpty()) {
                        return sb.toString().strip();
                    }
                    throw new IllegalArgumentException("No WAT function matching : " + f.format());
                }
                if (target instanceof org.babblelang.ir.IrTarget.Method m) {
                    String marker = "_" + sanitize(m.ownerName()) + "$" + sanitize(m.methodName());
                    for (WasmModule.FuncDef fn : module.definedFuncs()) {
                        String n = fn.name();
                        if (n != null && !n.endsWith("_generic") && (n.endsWith(marker) || n.contains(marker + "$"))) {
                            printFunc(sb, module, fn);
                        }
                    }
                    if (!sb.isEmpty()) {
                        return sb.toString().strip();
                    }
                    throw new IllegalArgumentException("No WAT method matching : " + m.format());
                }
                if (target instanceof org.babblelang.ir.IrTarget.TypeDef td) {
                    String suffix = "_" + sanitize(td.name());
                    for (WasmTypeDef.SubType sub : module.flatTypes()) {
                        if (sub != null && sub.name() != null
                                && (sub.name().equals(td.name()) || sub.name().endsWith(suffix))
                                && !sub.name().startsWith("ClassVal_")) {
                            printSubType(sb, module, sub);
                            sb.append('\n');
                        }
                    }
                    String methMarker = "_" + sanitize(td.name()) + "$";
                    for (WasmModule.FuncDef fn : module.definedFuncs()) {
                        String n = fn.name();
                        if (n != null && !n.endsWith("_generic") && !n.endsWith("$ctor_generic") && n.contains(methMarker)) {
                            printFunc(sb, module, fn);
                        }
                    }
                    if (!sb.isEmpty()) {
                        return sb.toString().strip();
                    }
                    throw new IllegalArgumentException("No WAT type matching : " + td.format());
                }
                return print(module).strip();
            }
        };
    }
}
