package org.babblelang.ir.mir;

import org.babblelang.ir.IrTarget;
import org.babblelang.ir.hir.HirFunction;

import java.util.Map;

/**
 * Raw debug printer for {@link MirModule} (used by {@link MirModule#printRaw()} and
 * {@code -Dbabble.dumpIr=mir}). Prints raw block IDs, value IDs, and function IDs without
 * canonicalization.
 */
public final class MirPrinter {
    private MirPrinter() {
    }

    public static String print(MirModule module) {
        StringBuilder sb = new StringBuilder();
        sb.append("mir module ").append(module.hir().sourceName()).append('\n');
        for (HirFunction hirFn : module.hir().functions().values()) {
            MirFunction fn = module.function(hirFn.id());
            if (fn == null) {
                String reason = module.fallbackReason(hirFn.id());
                sb.append("  fn #").append(hirFn.id()).append(' ')
                        .append(hirFn.name() != null ? hirFn.name() : "<anon>")
                        .append(" ; [not-lowered: ").append(reason != null ? reason : "unknown").append("]\n");
                continue;
            }
            printFunctionRaw(sb, fn);
        }
        return sb.toString().stripTrailing();
    }

    public static void printFunctionRaw(StringBuilder sb, MirFunction fn) {
        sb.append("  fn #").append(fn.hirFunctionId()).append(' ')
                .append(fn.name() != null ? fn.name() : "<anon>").append('(');
        for (int i = 0; i < fn.params().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(fn.params().get(i));
        }
        sb.append(") -> ").append(MirVal.kindLabel(fn.returnKind()))
                .append(" [entry=bb").append(fn.entryBlockId()).append("]:\n");
        for (MirBlock b : fn.blocks()) {
            sb.append("    bb").append(b.id());
            if (!b.params().isEmpty()) {
                sb.append('(');
                for (int i = 0; i < b.params().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(b.params().get(i));
                }
                sb.append(')');
            }
            if (b.handlerEntry()) {
                sb.append(" [handler]");
            }
            if (!b.handlers().isEmpty()) {
                sb.append(" [unwind: ");
                for (int i = 0; i < b.handlers().size(); i++) {
                    if (i > 0) sb.append(", ");
                    MirHandler h = b.handlers().get(i);
                    sb.append(h.catchJvmType() != null ? h.catchJvmType() : "*").append(" -> bb").append(h.handlerBlockId());
                }
                sb.append(']');
            }
            sb.append(":\n");
            for (MirInst inst : b.insts()) {
                sb.append("      ").append(inst).append('\n');
            }
            sb.append("      ").append(b.terminator()).append('\n');
        }
    }
}
