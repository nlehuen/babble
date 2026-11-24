package org.babblelang.compiler;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.runtime.JavaTypes;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Bytecode emission for all call forms (core collections, super calls, self tail/recursive
 * calls, direct Babble/this-method calls, JVM interface calls, Java constructor/method calls,
 * safe/dynamic calls, spread adaptation, and overload sets/dispatch), extracted from
 * {@link HirEmitter} (BIP027-I2).
 */
final class CallEmitter {
    private final HirEmitter emitter;

    CallEmitter(HirEmitter emitter) {
        this.emitter = emitter;
    }

    private MethodEmitContext ctx() {
        return emitter.ctx;
    }

    void finishCallCheck(ExprMeta meta) {
        MethodEmitContext c = ctx();
        String checked = meta.checkedType();
        if (checked != null) {
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            c.line(meta.line());
            emitter.controlOps.checkType(checked);
        }
    }

    // ---------------------------------------------------------------- core collections

    void emitCoreCollectionCall(HirExpr.CoreCollectionCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        String kind = c.collectionKind();
        List<HirCallArg> params = c.args();
        int count = params.size();
        String n0 = count > 0 ? params.get(0).name() : null;
        String n1 = count > 1 ? params.get(1).name() : null;
        String coreCollections = RUNTIME + "CoreCollections";
        mc.line(c.meta().line());
        if ("entry".equals(kind)) {
            if (count == 2) {
                int keyIdx = "value".equals(n0) && "key".equals(n1) ? 1 : 0;
                int valIdx = 1 - keyIdx;
                emitter.emitBoxed(params.get(keyIdx).value());
                emitter.emitBoxed(params.get(valIdx).value());
                mv.visitMethodInsn(INVOKESTATIC, coreCollections, "entry", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map$Entry;", false);
                mc.stackKind = PrimitiveKind.NONE;
                finishCallCheck(c.meta());
                return;
            }
        } else if (count == 0) {
            String method = switch (kind) {
                case "list" -> "listEmpty";
                case "set" -> "setEmpty";
                case "sortedset" -> "sortedSetEmpty";
                case "map" -> "mapEmpty";
                case "sortedmap" -> "sortedMapEmpty";
                default -> throw new IllegalStateException("Unknown core collection : " + kind);
            };
            String desc = switch (kind) {
                case "list" -> "()Ljava/util/List;";
                case "set" -> "()Ljava/util/Set;";
                case "sortedset" -> "()Ljava/util/SortedSet;";
                case "map" -> "()Ljava/util/Map;";
                case "sortedmap" -> "()Ljava/util/SortedMap;";
                default -> throw new IllegalStateException();
            };
            mv.visitMethodInsn(INVOKESTATIC, coreCollections, method, desc, false);
            mc.stackKind = PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        } else if (count == 1 && "capacity".equals(n0)) {
            emitter.emitAs(params.get(0).value(), PrimitiveKind.INT);
            String method = switch (kind) {
                case "list" -> "listCapacity";
                case "set" -> "setCapacity";
                case "map" -> "mapCapacity";
                default -> throw new IllegalStateException("capacity not supported for " + kind);
            };
            String desc = switch (kind) {
                case "list" -> "(I)Ljava/util/List;";
                case "set" -> "(I)Ljava/util/Set;";
                case "map" -> "(I)Ljava/util/Map;";
                default -> throw new IllegalStateException();
            };
            mv.visitMethodInsn(INVOKESTATIC, coreCollections, method, desc, false);
            mc.stackKind = PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        } else if (count == 1 && "comparator".equals(n0)) {
            emitter.emitBoxed(params.get(0).value());
            mv.visitVarInsn(ALOAD, mc.rtLocal);
            String method = "sortedset".equals(kind) ? "sortedSetComparator" : "sortedMapComparator";
            String desc = "sortedset".equals(kind)
                    ? "(Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedSet;"
                    : "(Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedMap;";
            mv.visitMethodInsn(INVOKESTATIC, coreCollections, method, desc, false);
            mc.stackKind = PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        } else if (count == 1 && "from".equals(n0)) {
            emitter.emitBoxed(params.get(0).value());
            if ("map".equals(kind) || "sortedmap".equals(kind)) {
                mv.visitTypeInsn(CHECKCAST, "java/util/Map");
            } else {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
            }
            switch (kind) {
                case "list" -> mv.visitMethodInsn(INVOKESTATIC, coreCollections, "listFrom", "(Ljava/lang/Iterable;)Ljava/util/List;", false);
                case "set" -> mv.visitMethodInsn(INVOKESTATIC, coreCollections, "setFrom", "(Ljava/lang/Iterable;)Ljava/util/Set;", false);
                case "map" -> mv.visitMethodInsn(INVOKESTATIC, coreCollections, "mapFrom", "(Ljava/util/Map;)Ljava/util/Map;", false);
                case "sortedset" -> {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, coreCollections, "sortedSetFrom",
                            "(Ljava/lang/Iterable;Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedSet;", false);
                }
                case "sortedmap" -> {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, coreCollections, "sortedMapFrom",
                            "(Ljava/util/Map;Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedMap;", false);
                }
                default -> throw new IllegalStateException();
            }
            mc.stackKind = PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        } else if (count == 2 && (("from".equals(n0) && "comparator".equals(n1)) || ("comparator".equals(n0) && "from".equals(n1)))) {
            int fromIdx = "from".equals(n0) ? 0 : 1;
            int compIdx = 1 - fromIdx;
            emitter.emitBoxed(params.get(fromIdx).value());
            if ("sortedmap".equals(kind)) {
                mv.visitTypeInsn(CHECKCAST, "java/util/Map");
            } else {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
            }
            emitter.emitBoxed(params.get(compIdx).value());
            mv.visitVarInsn(ALOAD, mc.rtLocal);
            if ("sortedset".equals(kind)) {
                mv.visitMethodInsn(INVOKESTATIC, coreCollections, "sortedSetFrom",
                        "(Ljava/lang/Iterable;Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedSet;", false);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, coreCollections, "sortedMapFrom",
                        "(Ljava/util/Map;Ljava/lang/Object;L" + RUNTIME + "ScriptRuntime;)Ljava/util/SortedMap;", false);
            }
            mc.stackKind = PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        }
        if (n0 == null && !"entry".equals(kind)) {
            emitArgumentsArray(params);
            String method = switch (kind) {
                case "list" -> "listOf";
                case "set" -> "setOf";
                case "sortedset" -> "sortedSetOf";
                case "map" -> "mapOf";
                case "sortedmap" -> "sortedMapOf";
                default -> throw new IllegalStateException();
            };
            String desc = switch (kind) {
                case "list" -> "([Ljava/lang/Object;)Ljava/util/List;";
                case "set" -> "([Ljava/lang/Object;)Ljava/util/Set;";
                case "sortedset" -> "([Ljava/lang/Object;)Ljava/util/SortedSet;";
                case "map" -> "([Ljava/lang/Object;)Ljava/util/Map;";
                case "sortedmap" -> "([Ljava/lang/Object;)Ljava/util/SortedMap;";
                default -> throw new IllegalStateException();
            };
            mv.visitMethodInsn(INVOKESTATIC, coreCollections, method, desc, false);
            mc.stackKind = PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        }
        mv.visitLdcInsn(kind);
        emitArgumentsArray(params);
        String[] names = callArgNames(params);
        if (names != null) {
            emitter.getStringArray(names);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitVarInsn(ALOAD, mc.rtLocal);
        mv.visitMethodInsn(INVOKESTATIC, coreCollections, "construct",
                "(Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/String;L" + RUNTIME + "ScriptRuntime;)Ljava/lang/Object;", false);
        mc.stackKind = PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    // ---------------------------------------------------------------- super, tail & direct Babble calls

    void emitSuperCall(HirExpr.SuperCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int ln = c.meta().line();
        List<HirCallArg> args = c.args();
        LoweringTargets.ArgumentBinding binding = c.binding();
        String[] names = binding != null ? null : callArgNames(args);
        LoweringTargets.SuperTargetInfo info = names == null ? c.targetInfo() : null;
        if (info != null && info.directBabbleTypeId() != null && info.providerBabbleTypeId() != null) {
            String directJvm = emitter.allInterfaceJvmNames.get(info.directBabbleTypeId());
            LoweringTargets.TypeHierarchyInfo provHierarchy = emitter.module.typeHierarchy(info.providerBabbleTypeId());
            LoweringTargets.MemberSpec provSpec = provHierarchy != null ? provHierarchy.interfaceMembers().get(info.memberName()) : null;
            LoweringTargets.FunctionSignature sig = provSpec != null && provSpec.callable() ? provSpec.declaredSignature() : null;
            int expectedParams = sig != null ? sig.parameterKinds().length : -1;
            boolean arityOk = sig != null && (binding != null ? binding.paramCount() == expectedParams : args.size() == expectedParams);
            if (directJvm != null && arityOk) {
                String desc = bridgeDescriptor(sig);
                if (emitter.bridges.interfaceHasMethod(info.directBabbleTypeId(), info.memberName(), desc)) {
                    List<HirExpr> argExprs = callArgExprs(args);
                    if (!info.ownerIsInterface() && mc.methodClass != null
                            && mc.methodClass.shape.id().equals(info.ownerTypeId())) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitVarInsn(ALOAD, mc.rtLocal);
                        emitBoundTypedArgs(argExprs, binding, sig.parameterKinds());
                        if (sig.hasOptionalMask()) {
                            ConstantPool.pushInt(mv, binding != null ? binding.missingParamBits() : 0);
                        }
                        mc.line(ln);
                        mv.visitMethodInsn(INVOKESPECIAL, directJvm, bridgeMethodName(info.memberName()), desc, true);
                        mc.stackKind = sig.returnKind();
                        finishCallCheck(c.meta());
                        return;
                    }
                    if (info.ownerIsInterface()) {
                        InterfaceGen ownerIface = emitter.interfaceGenByTypeId(info.ownerTypeId());
                        if (ownerIface != null) {
                            String helper = ownerIface.superHelper(directJvm, info.memberName(), sig);
                            emitter.loadVarOrName(c.thisVar(), "this", ln);
                            emitter.convertStack(mc.stackKind, PrimitiveKind.NONE);
                            mv.visitTypeInsn(CHECKCAST, ownerIface.name);
                            mv.visitVarInsn(ALOAD, mc.rtLocal);
                            emitBoundTypedArgs(argExprs, binding, sig.parameterKinds());
                            if (sig.hasOptionalMask()) {
                                ConstantPool.pushInt(mv, binding != null ? binding.missingParamBits() : 0);
                            }
                            mc.line(ln);
                            mv.visitMethodInsn(INVOKEINTERFACE, ownerIface.name, helper, desc, true);
                            mc.stackKind = sig.returnKind();
                            finishCallCheck(c.meta());
                            return;
                        }
                    } else if (c.ownerClassId() >= 0) {
                        ClassGen ownerCls = emitter.classGen(c.ownerClassId());
                        if (ownerCls != null) {
                            String helper = ownerCls.superHelper(directJvm, info.memberName(), sig);
                            emitter.loadVarOrName(c.thisVar(), "this", ln);
                            emitter.convertStack(mc.stackKind, PrimitiveKind.NONE);
                            mv.visitTypeInsn(CHECKCAST, ownerCls.name);
                            mv.visitVarInsn(ALOAD, mc.rtLocal);
                            emitBoundTypedArgs(argExprs, binding, sig.parameterKinds());
                            if (sig.hasOptionalMask()) {
                                ConstantPool.pushInt(mv, binding != null ? binding.missingParamBits() : 0);
                            }
                            mc.line(ln);
                            mv.visitMethodInsn(INVOKEVIRTUAL, ownerCls.name, helper, desc, false);
                            mc.stackKind = sig.returnKind();
                            finishCallCheck(c.meta());
                            return;
                        }
                    }
                }
            }
        }

        String site = emitter.constantPool.callSiteField(c.callSite());
        emitter.loadVarOrName(c.thisVar(), "this", ln);
        emitter.convertStack(mc.stackKind, PrimitiveKind.NONE);
        emitter.emitTypeRef(c.superTypeRef());
        mv.visitLdcInsn(c.memberName());
        mv.visitVarInsn(ALOAD, mc.rtLocal);
        if (binding != null) {
            emitBoundArgumentsArray(callArgExprs(args), binding);
            mv.visitInsn(ACONST_NULL);
        } else {
            emitArgumentsArray(args);
            if (names != null) {
                emitter.getStringArray(names);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
        }
        mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
        mc.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Supers", "call", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;L" + SCRIPT_RUNTIME
                + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", false);
        mc.stackKind = PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    void emitSelfTailCall(HirExpr.SelfTailCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        HirFunction fn = emitter.module.function(c.functionId());
        MethodEmitContext.TailLoop loop = mc.tailLoop;
        if (loop == null || mc.methodOwnerId != fn.id()) {
            throw new IllegalStateException("Self tail call of function #" + fn.id() + " outside of its body at line " + c.meta().line());
        }
        List<HirExpr> args = callArgExprs(c.args());
        LoweringTargets.ArgumentBinding binding = c.binding();
        int count = fn.params().size();
        if (loop.paramSlots() != null) {
            PrimitiveKind[] kinds = paramKindsOf(fn);
            emitBoundTypedArgs(args, binding, kinds);
            if (fn.hasOptionalMask()) {
                ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
                mv.visitVarInsn(ISTORE, loop.maskSlot());
            }
            for (int i = count - 1; i >= 0; i--) {
                storeKindOn(mv, kinds[i], loop.paramSlots()[i]);
            }
        } else if (fn.isSpread()) {
            emitBoundBoxedArgs(args, binding, count);
            for (int i = count - 1; i >= 0; i--) {
                mv.visitVarInsn(ASTORE, HirEmitter.FIRST_PARAMETER + i);
            }
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, HirEmitter.BINDER_LOCAL);
        } else {
            if (binding != null) {
                emitBoundArgumentsArray(args, binding);
            } else {
                emitArgumentsArray(c.args());
            }
            mv.visitVarInsn(ASTORE, HirEmitter.ARGS);
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, HirEmitter.NAMES);
        }
        mc.line(c.meta().line());
        mv.visitJumpInsn(GOTO, loop.head());
        mv.visitInsn(ACONST_NULL);
        mc.stackKind = PrimitiveKind.NONE;
    }

    void emitSelfRecurseCall(HirExpr.SelfRecurseCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        HirFunction fn = emitter.module.function(c.functionId());
        emitter.ensureFunctionScheduled(fn);
        int count = fn.params().size();
        int ln = c.meta().line();
        LoweringTargets.ArgumentBinding binding = c.binding();
        if (fn.hasSpec() && (binding != null ? binding.paramCount() == count : c.args().size() == count)) {
            PrimitiveKind[] kinds = paramKindsOf(fn);
            if (mc.methodClass != null) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, mc.selfLocal);
                mv.visitVarInsn(ALOAD, mc.rtLocal);
                emitBoundTypedArgs(c.args(), binding, kinds);
                if (fn.hasOptionalMask()) {
                    ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
                }
                mc.line(ln);
                mv.visitMethodInsn(INVOKEVIRTUAL, mc.methodClass.name, mc.methodClass.methodName(fn.memberName()) + "$spec",
                        methodSpecDescriptor(fn), false);
            } else {
                mv.visitVarInsn(ALOAD, mc.selfLocal);
                mv.visitVarInsn(ALOAD, mc.thizLocal);
                mv.visitVarInsn(ALOAD, mc.rtLocal);
                emitBoundTypedArgs(c.args(), binding, kinds);
                if (fn.hasOptionalMask()) {
                    ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
                }
                mc.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, emitter.className, fn.jvmMethodName() + "$spec",
                        functionSpecDescriptor(fn), false);
            }
            mc.stackKind = fn.returnKind();
            finishCallCheck(c.meta());
            return;
        }
        if (mc.methodClass != null) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, mc.selfLocal);
            mv.visitVarInsn(ALOAD, mc.rtLocal);
            mv.visitInsn(ACONST_NULL);
            emitBoundBoxedArgs(c.args(), binding, count);
            mc.line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, mc.methodClass.name, mc.methodClass.methodName(fn.memberName()),
                    methodDirectDescriptor(fn), false);
        } else {
            mv.visitVarInsn(ALOAD, mc.selfLocal);
            mv.visitVarInsn(ALOAD, mc.thizLocal);
            mv.visitVarInsn(ALOAD, mc.rtLocal);
            mv.visitInsn(ACONST_NULL);
            emitBoundBoxedArgs(c.args(), binding, count);
            mc.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, emitter.className, fn.jvmMethodName(), functionDirectDescriptor(fn), false);
        }
        mc.stackKind = PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    void emitDirectBabbleCall(HirExpr.DirectBabbleCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        HirFunction fn = emitter.module.function(c.targetFunctionId());
        emitter.ensureFunctionScheduled(fn);
        int count = fn.params().size();
        int ln = c.meta().line();
        LoweringTargets.ArgumentBinding binding = c.binding();
        emitter.emitBoxed(c.callee());
        mv.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
        mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNBOUND", "L" + RUNTIME + "Undefined;");
        mv.visitVarInsn(ALOAD, mc.rtLocal);
        if (fn.hasSpec()) {
            emitBoundTypedArgs(c.args(), binding, paramKindsOf(fn));
            if (fn.hasOptionalMask()) {
                ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
            }
            mc.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, emitter.className, fn.jvmMethodName() + "$spec", functionSpecDescriptor(fn), false);
            mc.stackKind = fn.returnKind();
        } else {
            mv.visitInsn(ACONST_NULL);
            emitBoundBoxedArgs(c.args(), binding, count);
            mc.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, emitter.className, fn.jvmMethodName(), functionDirectDescriptor(fn), false);
            mc.stackKind = PrimitiveKind.NONE;
        }
        finishCallCheck(c.meta());
    }

    void emitDirectThisMethodCall(HirExpr.DirectThisMethodCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int ln = c.meta().line();
        List<HirCallArg> args = c.args();
        LoweringTargets.ArgumentBinding binding = c.binding();
        String[] names = binding != null ? null : callArgNames(args);

        if (c.externalClassJvmName() != null) {
            emitExternalDirectMethodCall(c, ln, args, binding, names);
            return;
        }

        ClassGen gen = emitter.classGen(c.classId());
        HirFunction fn = emitter.module.function(c.targetFunctionId());
        emitter.ensureFunctionScheduled(fn);
        if (c.receiverText() != null) {
            emitter.emitBoxed(c.receiver());
            Label ok = new Label();
            mv.visitInsn(DUP);
            mv.visitJumpInsn(IFNONNULL, ok);
            mv.visitInsn(POP);
            mc.line(ln);
            ConstantPool.pushString(mv, c.receiverText());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "notAScope", "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
            mv.visitInsn(ATHROW);
            mv.visitLabel(ok);
            mv.visitTypeInsn(CHECKCAST, gen.name);
        } else {
            emitter.assigns.loadThisAs(c.receiver(), gen);
        }
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, gen.name, c.memberName(), "Ljava/lang/Object;");
        mv.visitLdcInsn(c.memberName());
        mc.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        if (c.overloadAltIndex() >= 0) {
            ConstantPool.pushInt(mv, c.overloadAltIndex());
            mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "memberOf", "(Ljava/lang/Object;I)L" + CALLABLE + ";", false);
        }
        mv.visitTypeInsn(CHECKCAST, COMPILED_METHOD);
        mv.visitVarInsn(ALOAD, mc.rtLocal);
        String jvmMethod = fn.jvmMethodName();
        int count = fn.params().size();
        if (fn.hasSpec() && (binding != null ? binding.paramCount() == count : (names == null && args.size() == count))) {
            emitBoundTypedArgs(callArgExprs(args), binding, paramKindsOf(fn));
            if (fn.hasOptionalMask()) {
                ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
            }
            mc.line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, jvmMethod + "$spec", methodSpecDescriptor(fn), false);
            mc.stackKind = fn.returnKind();
            finishCallCheck(c.meta());
            return;
        }
        if (fn.isSpread() && (binding != null ? binding.paramCount() == count : (names == null && args.size() <= count))) {
            mv.visitInsn(ACONST_NULL);
            emitBoundBoxedArgs(callArgExprs(args), binding, count);
            mc.line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, jvmMethod, methodDirectDescriptor(fn), false);
            mc.stackKind = PrimitiveKind.NONE;
            finishCallCheck(c.meta());
            return;
        }
        String site = emitter.constantPool.callSiteField(c.callSite());
        if (binding != null) {
            emitBoundArgumentsArray(callArgExprs(args), binding);
            mv.visitInsn(ACONST_NULL);
        } else {
            emitArgumentsArray(args);
            if (names != null) {
                emitter.getStringArray(names);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
        }
        mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
        mc.line(ln);
        mv.visitMethodInsn(INVOKEVIRTUAL, gen.name, jvmMethod + (fn.isSpread() ? "$call" : ""), METHOD_DESCRIPTOR, false);
        mc.stackKind = PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    private void emitExternalDirectMethodCall(HirExpr.DirectThisMethodCall c, int ln, List<HirCallArg> args,
                                              LoweringTargets.ArgumentBinding binding, String[] names) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        String ownerJvm = c.externalClassJvmName();
        LoweringTargets.FunctionSignature sig = c.externalSignature();
        String jvmMethod = c.overloadAltIndex() >= 0
                ? "m$" + c.memberName() + "$" + c.overloadAltIndex()
                : "m$" + c.memberName();
        emitter.emitBoxed(c.receiver());
        Label ok = new Label();
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNONNULL, ok);
        mv.visitInsn(POP);
        mc.line(ln);
        ConstantPool.pushString(mv, c.receiverText() != null ? c.receiverText() : "this");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "notAScope", "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(ok);
        mv.visitTypeInsn(CHECKCAST, ownerJvm);
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, ownerJvm, c.memberName(), "Ljava/lang/Object;");
        mv.visitLdcInsn(c.memberName());
        mc.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, CLASSES, "member", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        if (c.overloadAltIndex() >= 0) {
            ConstantPool.pushInt(mv, c.overloadAltIndex());
            mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "memberOf", "(Ljava/lang/Object;I)L" + CALLABLE + ";", false);
        }
        mv.visitTypeInsn(CHECKCAST, COMPILED_METHOD);
        mv.visitVarInsn(ALOAD, mc.rtLocal);
        int count = sig.parameterKinds().length;
        if (sig.hasSpec() && (binding != null ? binding.paramCount() == count : (names == null && args.size() == count))) {
            emitBoundTypedArgs(callArgExprs(args), binding, sig.parameterKinds());
            if (sig.hasOptionalMask()) {
                ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(sig.optionalParamBits(), count) : 0);
            }
            mc.line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, ownerJvm, jvmMethod + "$spec", methodSpecDescriptor(sig), false);
            mc.stackKind = sig.returnKind();
            finishCallCheck(c.meta());
            return;
        }
        mv.visitInsn(ACONST_NULL);
        emitBoundBoxedArgs(callArgExprs(args), binding, count);
        mc.line(ln);
        mv.visitMethodInsn(INVOKEVIRTUAL, ownerJvm, jvmMethod, methodDirectDescriptor(sig), false);
        mc.stackKind = PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    void emitRecordNew(HirExpr.RecordNew r) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int ln = r.meta().line();
        emitter.emitBoxed(r.classRef());
        if (r.classRefIsInstance()) {
            mv.visitTypeInsn(CHECKCAST, r.classJvmName());
            mv.visitFieldInsn(GETFIELD, r.classJvmName(), "$babble$class", "L" + BABBLE_CLASS + ";");
        } else {
            mv.visitTypeInsn(CHECKCAST, BABBLE_CLASS);
        }
        mv.visitVarInsn(ALOAD, mc.rtLocal);
        emitBoundTypedArgs(r.args(), r.binding(), r.paramKinds());
        mc.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, r.classJvmName(), "$new",
                fastRecordFactoryDescriptor(r.classJvmName(), r.paramKinds()), false);
        mc.stackKind = PrimitiveKind.NONE;
        finishCallCheck(r.meta());
    }

    void emitRecordReuse(HirExpr.RecordReuse r) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        org.babblelang.ir.hir.HirClass cls = r.classId() >= 0 ? emitter.module.clazz(r.classId()) : null;
        if (cls == null) {
            throw new IllegalStateException("RecordReuse requires local HirClass #" + r.classId());
        }
        int targetLocal = mc.newLocal();
        emitter.emitBoxed(r.target());
        mv.visitTypeInsn(CHECKCAST, r.classJvmName());
        mv.visitVarInsn(ASTORE, targetLocal);
        PrimitiveKind[] kinds = r.paramKinds();
        int[] argLocals = new int[r.args().size()];
        for (int i = 0; i < r.args().size(); i++) {
            emitter.emitAs(r.args().get(i), kinds[i]);
            argLocals[i] = mc.newLocal(kinds[i]);
            storeKindOn(mv, kinds[i], argLocals[i]);
        }
        for (int i = 0; i < r.args().size(); i++) {
            int memberIdx = cls.params().get(i).memberIndex();
            PrimitiveKind mk = cls.memberKinds()[memberIdx];
            mv.visitVarInsn(ALOAD, targetLocal);
            loadKindOn(mv, kinds[i], argLocals[i]);
            if (kinds[i] != mk) {
                if (kinds[i].isPrimitive() && !mk.isPrimitive()) {
                    boxOn(mv, kinds[i]);
                } else if (!kinds[i].isPrimitive() && mk.isPrimitive()) {
                    unboxOn(mv, mk);
                }
            }
            mv.visitFieldInsn(PUTFIELD, r.classJvmName(), cls.shape().member(memberIdx), jvmDescriptor(mk));
        }
        mv.visitVarInsn(ALOAD, targetLocal);
        mc.stackKind = PrimitiveKind.NONE;
        finishCallCheck(r.meta());
    }

    void emitInterfaceCall(HirExpr.InterfaceCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        String ifaceJvm = emitter.allInterfaceJvmNames.get(c.interfaceTypeId());
        LoweringTargets.FunctionSignature sig = c.signature();
        LoweringTargets.ArgumentBinding binding = c.binding();
        emitter.emitBoxed(c.receiver());
        Label ok = new Label();
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNONNULL, ok);
        mv.visitInsn(POP);
        mc.line(c.selectorLine());
        ConstantPool.pushString(mv, c.receiverText());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "notAScope", "(Ljava/lang/String;)L" + RUNTIME + "BabbleException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(ok);
        mv.visitTypeInsn(CHECKCAST, ifaceJvm);
        mv.visitVarInsn(ALOAD, mc.rtLocal);
        emitBoundTypedArgs(c.args(), binding, sig.parameterKinds());
        if (sig.hasOptionalMask()) {
            ConstantPool.pushInt(mv, binding != null ? binding.missingParamBits() : 0);
        }
        mc.line(c.meta().line());
        mv.visitMethodInsn(INVOKEINTERFACE, ifaceJvm, bridgeMethodName(c.memberName()), bridgeDescriptor(sig), true);
        mc.stackKind = sig.returnKind();
        finishCallCheck(c.meta());
    }

    // ---------------------------------------------------------------- Java calls

    private int[] emitJavaArgsToLocals(List<HirExpr> args, Class<?>[] paramTypes, boolean varArgs, int ln) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int[] argLocals = new int[paramTypes.length];
        if (!varArgs) {
            for (int i = 0; i < paramTypes.length; i++) {
                argLocals[i] = emitter.emitJavaArgToLocal(args.get(i), paramTypes[i], ln);
            }
            return argLocals;
        }
        int fixed = paramTypes.length - 1;
        for (int i = 0; i < fixed; i++) {
            argLocals[i] = emitter.emitJavaArgToLocal(args.get(i), paramTypes[i], ln);
        }
        Class<?> comp = paramTypes[fixed].getComponentType();
        int varCount = args.size() - fixed;
        int[] varLocals = new int[varCount];
        for (int j = 0; j < varCount; j++) {
            varLocals[j] = emitter.emitJavaArgToLocal(args.get(fixed + j), comp, ln);
        }
        ConstantPool.pushInt(mv, varCount);
        PrimitiveKind compKind = javaPrimitiveKind(comp);
        if (compKind.isPrimitive()) {
            mv.visitIntInsn(NEWARRAY, primArrayNewArrayType(compKind));
        } else {
            mv.visitTypeInsn(ANEWARRAY, Type.getInternalName(comp));
        }
        int storeOp = compKind.isPrimitive() ? primArrayStoreInsn(compKind) : AASTORE;
        for (int j = 0; j < varCount; j++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, j);
            emitter.loadJavaLocal(comp, varLocals[j]);
            mv.visitInsn(storeOp);
        }
        int arrLocal = mc.newLocal();
        mv.visitVarInsn(ASTORE, arrLocal);
        argLocals[fixed] = arrLocal;
        return argLocals;
    }

    void emitJavaConstructorCall(HirExpr.JavaConstructorCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int ln = c.meta().line();
        if (!c.pureStaticCallee()) {
            emitter.emitCheckedJavaReceiver(c.callee(), c.calleeText(), ln, false, OBJECT, "notAScope", null);
            mv.visitInsn(POP);
        }
        Constructor<?> ctor = c.target().constructor();
        Class<?>[] paramTypes = ctor.getParameterTypes();
        int[] argLocals = emitJavaArgsToLocals(c.args(), paramTypes, c.target().varArgs(), ln);
        String internalClass = Type.getInternalName(ctor.getDeclaringClass());
        mc.line(ln);
        mv.visitTypeInsn(NEW, internalClass);
        mv.visitInsn(DUP);
        for (int i = 0; i < paramTypes.length; i++) {
            emitter.loadJavaLocal(paramTypes[i], argLocals[i]);
        }
        mv.visitMethodInsn(INVOKESPECIAL, internalClass, "<init>", Type.getConstructorDescriptor(ctor), false);
        mv.visitMethodInsn(INVOKESTATIC, JAVA_OBJECT, "of", "(Ljava/lang/Object;)L" + JAVA_OBJECT + ";", false);
        mc.stackKind = PrimitiveKind.NONE;
        finishCallCheck(c.meta());
    }

    void emitJavaMethodCall(HirExpr.JavaMethodCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int ln = c.meta().line();
        LoweringTargets.JavaCallTarget target = c.target();
        Method method = target.method();
        String owner = Type.getInternalName(method.getDeclaringClass());
        int recvLocal = -1;
        if (target.isStatic()) {
            if (!c.pureStaticReceiver()) {
                emitter.emitCheckedJavaReceiver(c.receiver(), c.receiverText(), c.selectorLine(), false, OBJECT, "notAScope", null);
                mv.visitInsn(POP);
            }
        } else {
            emitter.emitCheckedJavaReceiver(c.receiver(), c.receiverText(), c.selectorLine(), target.unwrapReceiver(), owner, "notAScope", method.getDeclaringClass());
            recvLocal = mc.newLocal();
            mv.visitVarInsn(ASTORE, recvLocal);
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        int[] argLocals = emitJavaArgsToLocals(c.args(), paramTypes, target.varArgs(), ln);
        boolean itf = method.getDeclaringClass().isInterface();
        mc.line(ln);
        if (!target.isStatic()) {
            mv.visitVarInsn(ALOAD, recvLocal);
        }
        for (int i = 0; i < paramTypes.length; i++) {
            emitter.loadJavaLocal(paramTypes[i], argLocals[i]);
        }
        mv.visitMethodInsn(target.isStatic() ? INVOKESTATIC : (itf ? INVOKEINTERFACE : INVOKEVIRTUAL),
                owner, method.getName(), Type.getMethodDescriptor(method), itf);
        emitter.emitJavaValueToBabbleStack(method.getReturnType());
        finishCallCheck(c.meta());
    }

    // ---------------------------------------------------------------- safe & dynamic calls

    void emitSafeCall(HirExpr.SafeCall c) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        Label nonNull = new Label();
        Label end = new Label();
        emitter.emitBoxed(c.receiver());
        mv.visitInsn(DUP);
        mv.visitJumpInsn(IFNONNULL, nonNull);
        mv.visitInsn(POP);
        mv.visitInsn(ACONST_NULL);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(nonNull);
        emitter.assigns.selectReceiverMember(c.selectorLine(), c.receiverText(), c.memberName());
        if (c.selectorCheckedType() != null) {
            mc.line(c.selectorLine());
            emitter.controlOps.checkType(c.selectorCheckedType());
        }
        emitCallWithCallee(c.meta().line(), emitter.constantPool.callSiteField(c.callSite()),
                c.binding() != null ? null : callArgNames(c.args()), c.args(), c.binding(), c.babbleSig());
        emitter.convertStack(mc.stackKind, PrimitiveKind.NONE);
        if (c.meta().checkedType() != null) {
            mc.line(c.meta().line());
            emitter.controlOps.checkType(c.meta().checkedType());
        }
        mv.visitLabel(end);
        mc.stackKind = PrimitiveKind.NONE;
    }

    void emitDynamicCall(HirExpr.DynamicCall c) {
        emitter.emitBoxed(c.callee());
        emitCallWithCallee(c.meta().line(), emitter.constantPool.callSiteField(c.callSite()),
                c.binding() != null ? null : callArgNames(c.args()), c.args(), c.binding(), c.babbleSig());
        finishCallCheck(c.meta());
    }

    private void emitCallWithCallee(int lineNumber, String site, String[] names,
                                    List<HirCallArg> parameters, LoweringTargets.ArgumentBinding binding,
                                    LoweringTargets.FunctionSignature sig) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        mc.line(lineNumber);
        mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);

        mv.visitVarInsn(ALOAD, mc.rtLocal);
        if (sig != null && names == null
                && (binding != null ? binding.paramCount() == sig.parameterKinds().length : parameters.size() == sig.parameterKinds().length)) {
            int missingBits = binding != null ? binding.missingParamBits() : 0;
            mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
            StringBuilder desc = new StringBuilder("(L").append(CALLABLE).append(";L").append(SCRIPT_RUNTIME)
                    .append(";L").append(CALL_SITE).append(';');
            for (PrimitiveKind pk : sig.parameterKinds()) {
                desc.append(jvmDescriptor(pk));
            }
            desc.append(')').append(jvmDescriptor(sig.returnKind()));
            emitBoundTypedArgs(callArgExprs(parameters), binding, sig.parameterKinds());
            mc.line(lineNumber);
            if (missingBits != 0) {
                mv.visitInvokeDynamicInsn("typedCallOpt", desc.toString(), TYPED_CALL_OPT_BOOTSTRAP, missingBits);
            } else {
                mv.visitInvokeDynamicInsn("typedCall", desc.toString(), TYPED_CALL_BOOTSTRAP);
            }
            mc.stackKind = sig.returnKind();
            return;
        }
        if (binding != null) {
            int effective = effectiveParamCount(binding);
            if (effective <= HirFunction.MAX_SPREAD) {
                mv.visitInsn(ACONST_NULL);
                mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
                emitBoundBoxedArgs(callArgExprs(parameters), binding, effective, true);
                mc.line(lineNumber);
                mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";"
                        + "Ljava/lang/Object;".repeat(effective) + ")Ljava/lang/Object;", CALL_BOOTSTRAP);
                mc.stackKind = PrimitiveKind.NONE;
                return;
            }
            emitBoundArgumentsArray(callArgExprs(parameters), binding);
            mv.visitInsn(ACONST_NULL);
            mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
            mc.line(lineNumber);
            mv.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call", "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
            mc.stackKind = PrimitiveKind.NONE;
            return;
        }
        if (parameters.size() <= HirFunction.MAX_SPREAD) {
            if (names != null) {
                emitter.getStringArray(names);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
            for (HirCallArg parameter : parameters) {
                emitter.emitBoxed(parameter.value());
            }
            mc.line(lineNumber);
            mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";"
                    + "Ljava/lang/Object;".repeat(parameters.size()) + ")Ljava/lang/Object;", CALL_BOOTSTRAP);
            mc.stackKind = PrimitiveKind.NONE;
            return;
        }
        emitArgumentsArray(parameters);
        if (names != null) {
            emitter.getStringArray(names);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
        mc.line(lineNumber);
        mv.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call", "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
        mc.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- unified bound-argument marshaling

    private record BoundArgTemps(int[] locals, PrimitiveKind[] kinds) {
    }

    /**
     * Evaluates reordered or rest-bearing call arguments left-to-right into fresh temporary
     * locals of their target {@link PrimitiveKind}. Shared by typed, boxed, and array-packed
     * bound-argument emitters.
     */
    private BoundArgTemps evaluateBoundArgsToTemps(List<HirExpr> sourceArgs,
                                                   LoweringTargets.ArgumentBinding binding,
                                                   PrimitiveKind[] targetKinds) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        PrimitiveKind[] argKinds = new PrimitiveKind[sourceArgs.size()];
        Arrays.fill(argKinds, PrimitiveKind.NONE);
        if (targetKinds != null) {
            for (int p = 0; p < targetKinds.length && p < map.length; p++) {
                if (p != restParam && map[p] >= 0 && map[p] < argKinds.length) {
                    argKinds[map[p]] = targetKinds[p];
                }
            }
        }
        if (restParam >= 0 && !anyTrue(binding.restArgIsSpread()) && binding.restElementKind().isPrimitive()) {
            for (int a : binding.restArgs()) {
                argKinds[a] = binding.restElementKind();
            }
        }
        int[] tempLocals = new int[sourceArgs.size()];
        for (int a = 0; a < sourceArgs.size(); a++) {
            emitter.emitAs(sourceArgs.get(a), argKinds[a]);
            tempLocals[a] = mc.newLocal(argKinds[a]);
            storeKindOn(mv, argKinds[a], tempLocals[a]);
        }
        return new BoundArgTemps(tempLocals, argKinds);
    }

    private void emitBoundTypedArgs(List<HirExpr> sourceArgs, LoweringTargets.ArgumentBinding binding,
                                    PrimitiveKind[] targetKinds) {
        MethodVisitor mv = ctx().mv;
        if (binding == null || (!binding.reordered() && !binding.hasRest())) {
            for (int p = 0; p < targetKinds.length; p++) {
                boolean supplied = binding != null
                        ? (p < binding.paramToArg().length && binding.paramToArg()[p] >= 0)
                        : (p < sourceArgs.size());
                if (supplied) {
                    emitter.emitAs(sourceArgs.get(p), targetKinds[p]);
                } else {
                    emitDefaultValueOn(mv, targetKinds[p]);
                }
            }
            return;
        }
        BoundArgTemps temps = evaluateBoundArgsToTemps(sourceArgs, binding, targetKinds);
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        for (int p = 0; p < targetKinds.length; p++) {
            if (p == restParam) {
                emitPackedRestFromTemps(binding, temps.locals(), temps.kinds(), false, false);
                continue;
            }
            int a = p < map.length ? map[p] : -1;
            if (a >= 0) {
                loadKindOn(mv, targetKinds[p], temps.locals()[a]);
            } else {
                emitDefaultValueOn(mv, targetKinds[p]);
            }
        }
    }

    private void emitBoundBoxedArgs(List<HirExpr> sourceArgs, LoweringTargets.ArgumentBinding binding, int count) {
        emitBoundBoxedArgs(sourceArgs, binding, count, false);
    }

    private void emitBoundBoxedArgs(List<HirExpr> sourceArgs, LoweringTargets.ArgumentBinding binding,
                                    int count, boolean wrapPackedRest) {
        MethodVisitor mv = ctx().mv;
        if (binding == null || (!binding.reordered() && !binding.hasRest())) {
            for (int p = 0; p < count; p++) {
                boolean supplied = binding != null
                        ? (p < binding.paramToArg().length && binding.paramToArg()[p] >= 0)
                        : (p < sourceArgs.size());
                if (supplied) {
                    emitter.emitBoxed(sourceArgs.get(p));
                } else {
                    mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                }
            }
            return;
        }
        BoundArgTemps temps = evaluateBoundArgsToTemps(sourceArgs, binding, null);
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        for (int p = 0; p < count; p++) {
            if (p == restParam) {
                emitPackedRestFromTemps(binding, temps.locals(), temps.kinds(), wrapPackedRest, false);
                continue;
            }
            int a = p < map.length ? map[p] : -1;
            if (a >= 0) {
                mv.visitVarInsn(ALOAD, temps.locals()[a]);
            } else {
                mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
            }
        }
    }

    private void emitBoundArgumentsArray(List<HirExpr> sourceArgs, LoweringTargets.ArgumentBinding binding) {
        MethodVisitor mv = ctx().mv;
        int effective = effectiveParamCount(binding);
        if (effective == 0) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        if (!binding.reordered() && !binding.hasRest()) {
            ConstantPool.pushInt(mv, effective);
            mv.visitTypeInsn(ANEWARRAY, OBJECT);
            for (int p = 0; p < effective; p++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, p);
                emitter.emitBoxed(sourceArgs.get(p));
                mv.visitInsn(AASTORE);
            }
            return;
        }
        BoundArgTemps temps = evaluateBoundArgsToTemps(sourceArgs, binding, null);
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        ConstantPool.pushInt(mv, effective);
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int p = 0; p < effective; p++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, p);
            if (p == restParam) {
                emitPackedRestFromTemps(binding, temps.locals(), temps.kinds(), true, false);
            } else {
                int a = map[p];
                if (a >= 0) {
                    mv.visitVarInsn(ALOAD, temps.locals()[a]);
                } else {
                    mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                }
            }
            mv.visitInsn(AASTORE);
        }
    }

    private void emitPackedRestFromTemps(LoweringTargets.ArgumentBinding binding, int[] tempLocals,
                                         PrimitiveKind[] argKinds, boolean wrapPackedRest,
                                         boolean applyArgAdapts) {
        MethodVisitor mv = ctx().mv;
        int[] restArgs = binding.restArgs();
        boolean[] isSpread = binding.restArgIsSpread();
        int n = restArgs.length;
        if (n == 0) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS_VIEW, "EMPTY", "L" + ARGUMENTS_VIEW + ";");
        } else if (n == 1 && isSpread[0]) {
            mv.visitVarInsn(ALOAD, tempLocals[restArgs[0]]);
            if (applyArgAdapts) {
                emitSpreadAdaptOnStack(binding.adaptForArg(restArgs[0]));
            }
            mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asIterable", "(Ljava/lang/Object;)Ljava/lang/Iterable;", false);
        } else if (!anyTrue(isSpread)) {
            PrimitiveKind rk = binding.restElementKind();
            if (rk.isPrimitive()) {
                ConstantPool.pushInt(mv, n);
                mv.visitIntInsn(NEWARRAY, primArrayNewArrayType(rk));
                for (int k = 0; k < n; k++) {
                    mv.visitInsn(DUP);
                    ConstantPool.pushInt(mv, k);
                    loadKindOn(mv, rk, tempLocals[restArgs[k]]);
                    mv.visitInsn(primArrayStoreInsn(rk));
                }
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS_VIEW, primViewFactory(rk),
                        "(" + primArrayDesc(rk) + ")L" + ARGUMENTS_VIEW + ";", false);
            } else {
                ConstantPool.pushInt(mv, n);
                mv.visitTypeInsn(ANEWARRAY, OBJECT);
                for (int k = 0; k < n; k++) {
                    mv.visitInsn(DUP);
                    ConstantPool.pushInt(mv, k);
                    loadKindOn(mv, argKinds[restArgs[k]], tempLocals[restArgs[k]]);
                    boxOn(mv, argKinds[restArgs[k]]);
                    if (applyArgAdapts) {
                        emitSpreadAdaptOnStack(binding.adaptForArg(restArgs[k]));
                    }
                    mv.visitInsn(AASTORE);
                }
                mv.visitMethodInsn(INVOKESTATIC, ARGUMENTS_VIEW, "of",
                        "([Ljava/lang/Object;)L" + ARGUMENTS_VIEW + ";", false);
            }
        } else {
            ConstantPool.pushInt(mv, n);
            mv.visitTypeInsn(ANEWARRAY, OBJECT);
            for (int k = 0; k < n; k++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, k);
                loadKindOn(mv, argKinds[restArgs[k]], tempLocals[restArgs[k]]);
                boxOn(mv, argKinds[restArgs[k]]);
                if (applyArgAdapts) {
                    emitSpreadAdaptOnStack(binding.adaptForArg(restArgs[k]));
                }
                mv.visitInsn(AASTORE);
            }
            ConstantPool.pushInt(mv, n);
            mv.visitIntInsn(NEWARRAY, T_BOOLEAN);
            for (int k = 0; k < n; k++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, k);
                mv.visitInsn(isSpread[k] ? ICONST_1 : ICONST_0);
                mv.visitInsn(BASTORE);
            }
            mv.visitMethodInsn(INVOKESTATIC, SPREADS, "packRest",
                    "([Ljava/lang/Object;[Z)Ljava/lang/Iterable;", false);
        }
        if (wrapPackedRest) {
            mv.visitMethodInsn(INVOKESTATIC, SPREADS, "packedRest",
                    "(Ljava/lang/Iterable;)Ljava/lang/Object;", false);
        }
    }

    // ---------------------------------------------------------------- spreads & overloads

    void emitSpreadAdapt(HirExpr.SpreadAdapt expr) {
        MethodEmitContext mc = ctx();
        emitter.emitBoxed(expr.value());
        emitSpreadAdaptOnStack(expr.adapt());
        mc.stackKind = PrimitiveKind.NONE;
    }

    private void emitSpreadAdaptOnStack(LoweringTargets.SpreadAdapt adapt) {
        if (adapt == null) {
            return;
        }
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        LoweringTargets.JavaSamTarget elemSam = adapt.elementSamTarget();
        switch (adapt.kind()) {
            case NONE, AS_IS -> {
            }
            case TO_ITERABLE -> {
                if (elemSam == null) {
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asIterable",
                            "(Ljava/lang/Object;)Ljava/lang/Iterable;", false);
                } else if (isSamTargetAccessible(elemSam)) {
                    pushSamTargetArgs(elemSam);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asIterable",
                            "(Ljava/lang/Object;Ljava/lang/Class;[Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Iterable;", false);
                } else {
                    pushSamTargetNames(elemSam);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asIterableByName",
                            "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/String;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Iterable;", false);
                }
            }
            case TO_LIST_BORROWED, TO_LIST_COPY -> {
                if (elemSam == null) {
                    mv.visitInsn(adapt.kind() == LoweringTargets.SpreadKind.TO_LIST_BORROWED ? ICONST_1 : ICONST_0);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asList", "(Ljava/lang/Object;Z)Ljava/util/List;", false);
                } else if (isSamTargetAccessible(elemSam)) {
                    mv.visitInsn(ICONST_0);
                    pushSamTargetArgs(elemSam);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asList",
                            "(Ljava/lang/Object;ZLjava/lang/Class;[Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/util/List;", false);
                } else {
                    mv.visitInsn(ICONST_0);
                    pushSamTargetNames(elemSam);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asListByName",
                            "(Ljava/lang/Object;ZLjava/lang/String;[Ljava/lang/String;L" + SCRIPT_RUNTIME + ";)Ljava/util/List;", false);
                }
            }
            case TO_ARRAY -> {
                Class<?> comp = adapt.arrayComponentType() != null ? adapt.arrayComponentType() : Object.class;
                if (elemSam == null) {
                    emitPushClassConstant(mv, comp);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asArray",
                            "(Ljava/lang/Object;Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
                } else if (isSamTargetAccessible(elemSam) && (comp.isPrimitive() || JavaTypes.isAccessible(comp))) {
                    emitPushClassConstant(mv, comp);
                    pushSamTargetArgs(elemSam);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asArray",
                            "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
                } else if (comp.isPrimitive() || JavaTypes.isAccessible(comp)) {
                    emitPushClassConstant(mv, comp);
                    pushSamTargetNames(elemSam);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asArrayByName",
                            "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/String;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
                } else {
                    mv.visitLdcInsn(comp.getName());
                    pushSamTargetNames(elemSam);
                    mv.visitVarInsn(ALOAD, mc.rtLocal);
                    mv.visitMethodInsn(INVOKESTATIC, SPREADS, "asArrayByNames",
                            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
                }
            }
            case TO_SAM -> {
                if (elemSam != null) {
                    emitter.controlOps.emitToJavaSamOnStack(elemSam, -1);
                }
            }
        }
    }

    private static boolean isSamTargetAccessible(LoweringTargets.JavaSamTarget target) {
        if (!JavaTypes.isAccessible(target.samInterface())) {
            return false;
        }
        for (Class<?> marker : target.markers()) {
            if (!JavaTypes.isAccessible(marker)) {
                return false;
            }
        }
        return true;
    }

    private void pushSamTargetArgs(LoweringTargets.JavaSamTarget target) {
        MethodVisitor mv = ctx().mv;
        mv.visitLdcInsn(Type.getType(target.samInterface()));
        Class<?>[] markers = target.markers();
        if (markers.length == 0) {
            mv.visitInsn(ACONST_NULL);
        } else {
            mv.visitFieldInsn(GETSTATIC, emitter.className, emitter.constantPool.classArrayField(markers), "[Ljava/lang/Class;");
        }
    }

    private void pushSamTargetNames(LoweringTargets.JavaSamTarget target) {
        MethodVisitor mv = ctx().mv;
        mv.visitLdcInsn(target.samInterface().getName());
        Class<?>[] markers = target.markers();
        if (markers.length == 0) {
            mv.visitInsn(ACONST_NULL);
        } else {
            String[] names = new String[markers.length];
            for (int i = 0; i < markers.length; i++) {
                names[i] = markers[i].getName();
            }
            mv.visitFieldInsn(GETSTATIC, emitter.className, emitter.constantPool.stringArrayField(names), "[Ljava/lang/String;");
        }
    }

    void emitMakeOverloadSet(HirExpr.MakeOverloadSet m) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        List<HirExpr> ops = m.operands();
        if (mc.constructorClass != null && isAllMethodRefsOf(ops, mc.constructorClass.cls.id())) {
            String field = emitter.cachedMethodOverloadField(m, mc.constructorClass);
            mv.visitFieldInsn(GETSTATIC, emitter.className, field, "L" + OVERLOAD_SET + ";");
            mc.stackKind = PrimitiveKind.NONE;
            return;
        }
        // 1. Object[] operands
        ConstantPool.pushInt(mv, ops.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < ops.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            emitter.emitBoxed(ops.get(i));
            mv.visitInsn(AASTORE);
        }
        mc.line(m.meta().line());
        emitOverloadSetMetaAndCreate(mv, m.metas());
        mc.stackKind = PrimitiveKind.NONE;
    }

    private boolean isAllMethodRefsOf(List<HirExpr> ops, int classId) {
        if (ops.isEmpty()) {
            return false;
        }
        for (HirExpr op : ops) {
            if (!(op instanceof HirExpr.FunctionRef ref)) {
                return false;
            }
            HirFunction fn = emitter.module.function(ref.functionId());
            if (fn == null || fn.ownerClassId() != classId) {
                return false;
            }
        }
        return true;
    }

    static void emitOverloadSetMetaAndCreate(MethodVisitor mv, List<LoweringTargets.OverloadAltMetaInfo> metas) {
        // 2. String[] sigKeys
        ConstantPool.pushInt(mv, metas.size());
        mv.visitTypeInsn(ANEWARRAY, STRING);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            mv.visitLdcInsn(metas.get(i).signatureKey());
            mv.visitInsn(AASTORE);
        }
        // 3. String[] displays
        ConstantPool.pushInt(mv, metas.size());
        mv.visitTypeInsn(ANEWARRAY, STRING);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            mv.visitLdcInsn(metas.get(i).display());
            mv.visitInsn(AASTORE);
        }
        // 4. int[] minArities
        ConstantPool.pushInt(mv, metas.size());
        mv.visitIntInsn(NEWARRAY, T_INT);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            ConstantPool.pushInt(mv, metas.get(i).minArity());
            mv.visitInsn(IASTORE);
        }
        // 5. int[] maxArities
        ConstantPool.pushInt(mv, metas.size());
        mv.visitIntInsn(NEWARRAY, T_INT);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            ConstantPool.pushInt(mv, metas.get(i).maxArity());
            mv.visitInsn(IASTORE);
        }
        // 6. int[] restIndices
        ConstantPool.pushInt(mv, metas.size());
        mv.visitIntInsn(NEWARRAY, T_INT);
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            ConstantPool.pushInt(mv, metas.get(i).restIndex());
            mv.visitInsn(IASTORE);
        }
        // 7. String[][] paramDescriptors
        ConstantPool.pushInt(mv, metas.size());
        mv.visitTypeInsn(ANEWARRAY, "[L" + STRING + ";");
        for (int i = 0; i < metas.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            String[] descs = metas.get(i).paramDescriptors();
            ConstantPool.pushInt(mv, descs.length);
            mv.visitTypeInsn(ANEWARRAY, STRING);
            for (int j = 0; j < descs.length; j++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, j);
                mv.visitLdcInsn(descs[j] != null ? descs[j] : "");
                mv.visitInsn(AASTORE);
            }
            mv.visitInsn(AASTORE);
        }
        mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "create",
                "([Ljava/lang/Object;[Ljava/lang/String;[Ljava/lang/String;[I[I[I[[Ljava/lang/String;)L" + OVERLOAD_SET + ";",
                false);
    }

    void emitOverloadSelect(HirExpr.OverloadSelect s) {
        MethodEmitContext mc = ctx();
        emitter.emitBoxed(s.set());
        emitOverloadSelectOnStack(s.index(), s.signatureKey(), s.exactSet());
        mc.stackKind = PrimitiveKind.NONE;
    }

    private void emitOverloadSelectOnStack(int index, String signatureKey, boolean exactSet) {
        MethodVisitor mv = ctx().mv;
        if (exactSet) {
            ConstantPool.pushInt(mv, index);
            mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "memberOf",
                    "(Ljava/lang/Object;I)L" + CALLABLE + ";", false);
        } else {
            mv.visitLdcInsn(signatureKey);
            mv.visitMethodInsn(INVOKESTATIC, OVERLOAD_SET, "selectFrom",
                    "(Ljava/lang/Object;Ljava/lang/String;)L" + CALLABLE + ";", false);
        }
    }

    void emitOverloadDispatch(HirExpr.OverloadDispatch d) {
        MethodEmitContext mc = ctx();
        MethodVisitor mv = mc.mv;
        int savedNextLocal = mc.nextLocal;
        int ln = d.meta().line();
        int calleeLocal = mc.newLocal();
        emitter.emitBoxed(d.callee());
        mv.visitVarInsn(ASTORE, calleeLocal);

        List<HirExpr> args = d.args();
        int[] argLocals = new int[args.size()];
        PrimitiveKind[] argKinds = new PrimitiveKind[args.size()];
        for (int i = 0; i < args.size(); i++) {
            argLocals[i] = mc.newLocal();
            argKinds[i] = PrimitiveKind.NONE;
            emitter.emitBoxed(args.get(i));
            mv.visitVarInsn(ASTORE, argLocals[i]);
        }

        PrimitiveKind joinKind = d.meta().kind();
        Label endLabel = new Label();
        String siteField = emitter.constantPool.callSiteField(d.callSite());

        for (HirExpr.OverloadBranch branch : d.branches()) {
            Label nextBranch = new Label();
            for (LoweringTargets.OverloadSplitCheck chk : branch.checks()) {
                int slot = argLocals[chk.argIndex()];
                if (chk.isNull()) {
                    mv.visitVarInsn(ALOAD, slot);
                    mv.visitJumpInsn(IFNONNULL, nextBranch);
                } else {
                    mv.visitVarInsn(ALOAD, slot);
                    mv.visitJumpInsn(IFNULL, nextBranch);
                    for (String desc : chk.descriptors()) {
                        mv.visitVarInsn(ALOAD, slot);
                        mv.visitLdcInsn(desc);
                        mv.visitMethodInsn(INVOKESTATIC, TYPES, "matches", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
                        mv.visitJumpInsn(IFEQ, nextBranch);
                    }
                }
            }

            LoweringTargets.ArgumentBinding binding = branch.binding();
            if (branch.directFunctionId() >= 0) {
                HirFunction fn = emitter.module.function(branch.directFunctionId());
                emitter.ensureFunctionScheduled(fn);
                int count = fn.params().size();
                mv.visitVarInsn(ALOAD, calleeLocal);
                emitOverloadSelectOnStack(branch.altIndex(), branch.signatureKey(), branch.exactSet());
                mv.visitTypeInsn(CHECKCAST, COMPILED_FUNCTION);
                mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNBOUND", "L" + RUNTIME + "Undefined;");
                mv.visitVarInsn(ALOAD, mc.rtLocal);
                if (fn.hasSpec()) {
                    PrimitiveKind[] paramKinds = paramKindsOf(fn);
                    for (int p = 0; p < count; p++) {
                        if (binding != null && p == binding.restParam()) {
                            emitPackedRestFromTemps(binding, argLocals, argKinds, false, true);
                        } else {
                            int a = binding != null ? binding.paramToArg()[p] : p;
                            if (a >= 0) {
                                mv.visitVarInsn(ALOAD, argLocals[a]);
                                if (binding != null) {
                                    emitSpreadAdaptOnStack(binding.adaptForArg(a));
                                }
                                if (paramKinds[p].isPrimitive()) {
                                    unboxOn(mv, paramKinds[p]);
                                }
                            } else {
                                emitDefaultValueOn(mv, paramKinds[p]);
                            }
                        }
                    }
                    if (fn.hasOptionalMask()) {
                        ConstantPool.pushInt(mv, binding != null ? binding.specMaskFor(fn.optionalParamBits(), count) : 0);
                    }
                    mc.line(ln);
                    mv.visitMethodInsn(INVOKESTATIC, emitter.className, fn.jvmMethodName() + "$spec", functionSpecDescriptor(fn), false);
                    emitter.convertStack(fn.returnKind(), joinKind);
                } else {
                    mv.visitInsn(ACONST_NULL);
                    for (int p = 0; p < count; p++) {
                        if (binding != null && p == binding.restParam()) {
                            emitPackedRestFromTemps(binding, argLocals, argKinds, false, true);
                        } else {
                            int a = binding != null ? binding.paramToArg()[p] : p;
                            if (a >= 0) {
                                mv.visitVarInsn(ALOAD, argLocals[a]);
                                if (binding != null) {
                                    emitSpreadAdaptOnStack(binding.adaptForArg(a));
                                }
                            } else {
                                mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                            }
                        }
                    }
                    mc.line(ln);
                    mv.visitMethodInsn(INVOKESTATIC, emitter.className, fn.jvmMethodName(), functionDirectDescriptor(fn), false);
                    emitter.convertStack(PrimitiveKind.NONE, joinKind);
                }
            } else {
                mv.visitVarInsn(ALOAD, calleeLocal);
                emitOverloadSelectOnStack(branch.altIndex(), branch.signatureKey(), branch.exactSet());
                mv.visitVarInsn(ALOAD, mc.rtLocal);
                emitBoundArgsArrayFromTemps(binding, argLocals, argKinds);
                mv.visitInsn(ACONST_NULL);
                mv.visitFieldInsn(GETSTATIC, emitter.className, siteField, "L" + CALL_SITE + ";");
                mc.line(ln);
                mv.visitMethodInsn(INVOKEINTERFACE, CALLABLE, "call",
                        "(L" + SCRIPT_RUNTIME + ";[Ljava/lang/Object;[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", true);
                emitter.convertStack(PrimitiveKind.NONE, joinKind);
            }
            mv.visitJumpInsn(GOTO, endLabel);
            mv.visitLabel(nextBranch);
        }

        // Unreachable when static split is exhaustive; keeps JVM stack frames valid.
        mv.visitTypeInsn(NEW, BABBLE_EXCEPTION);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("No applicable overload of " + d.calleeText());
        mv.visitMethodInsn(INVOKESPECIAL, BABBLE_EXCEPTION, "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);

        mv.visitLabel(endLabel);
        mc.nextLocal = savedNextLocal;
        mc.stackKind = joinKind;
        finishCallCheck(d.meta());
    }

    private void emitBoundArgsArrayFromTemps(LoweringTargets.ArgumentBinding binding, int[] argLocals,
                                             PrimitiveKind[] argKinds) {
        MethodVisitor mv = ctx().mv;
        if (binding == null) {
            ConstantPool.pushInt(mv, argLocals.length);
            mv.visitTypeInsn(ANEWARRAY, OBJECT);
            for (int i = 0; i < argLocals.length; i++) {
                mv.visitInsn(DUP);
                ConstantPool.pushInt(mv, i);
                mv.visitVarInsn(ALOAD, argLocals[i]);
                mv.visitInsn(AASTORE);
            }
            return;
        }
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        int effective = effectiveParamCount(binding);
        if (effective == 0) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(mv, effective);
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int p = 0; p < effective; p++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, p);
            if (p == restParam) {
                emitPackedRestFromTemps(binding, argLocals, argKinds, true, true);
            } else {
                int a = map[p];
                if (a >= 0) {
                    mv.visitVarInsn(ALOAD, argLocals[a]);
                    emitSpreadAdaptOnStack(binding.adaptForArg(a));
                } else {
                    mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "MISSING", "Ljava/lang/Object;");
                }
            }
            mv.visitInsn(AASTORE);
        }
    }

    // ---------------------------------------------------------------- small helpers

    private void emitArgumentsArray(List<HirCallArg> parameters) {
        MethodVisitor mv = ctx().mv;
        if (parameters.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ARGUMENTS, "NONE", "[Ljava/lang/Object;");
            return;
        }
        ConstantPool.pushInt(mv, parameters.size());
        mv.visitTypeInsn(ANEWARRAY, OBJECT);
        for (int i = 0; i < parameters.size(); i++) {
            mv.visitInsn(DUP);
            ConstantPool.pushInt(mv, i);
            emitter.emitBoxed(parameters.get(i).value());
            mv.visitInsn(AASTORE);
        }
    }

    private static List<HirExpr> callArgExprs(List<HirCallArg> args) {
        if (args.isEmpty()) {
            return List.of();
        }
        List<HirExpr> out = new ArrayList<>(args.size());
        for (HirCallArg a : args) {
            out.add(a.value());
        }
        return out;
    }

    private static PrimitiveKind[] paramKindsOf(HirFunction fn) {
        List<HirParam> params = fn.params();
        PrimitiveKind[] kinds = new PrimitiveKind[params.size()];
        for (int i = 0; i < params.size(); i++) {
            kinds[i] = params.get(i).kind();
        }
        return kinds;
    }

    private static int effectiveParamCount(LoweringTargets.ArgumentBinding binding) {
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        int eff = map.length;
        while (eff > 0 && eff - 1 != restParam && map[eff - 1] < 0) {
            eff--;
        }
        return eff;
    }

    private static boolean anyTrue(boolean[] flags) {
        if (flags == null) {
            return false;
        }
        for (boolean f : flags) {
            if (f) {
                return true;
            }
        }
        return false;
    }

    private static String[] callArgNames(List<HirCallArg> parameters) {
        String[] names = null;
        for (int i = 0; i < parameters.size(); i++) {
            String name = parameters.get(i).name();
            if (name != null) {
                if (names == null) {
                    names = new String[parameters.size()];
                }
                names[i] = name;
            }
        }
        return names;
    }

    private static int primArrayNewArrayType(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> T_BYTE;
            case SHORT -> T_SHORT;
            case INT -> T_INT;
            case LONG -> T_LONG;
            case FLOAT -> T_FLOAT;
            case DOUBLE -> T_DOUBLE;
            case BOOL -> T_BOOLEAN;
            case CHAR -> T_CHAR;
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static int primArrayStoreInsn(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, BOOL -> BASTORE;
            case SHORT -> SASTORE;
            case INT -> IASTORE;
            case LONG -> LASTORE;
            case FLOAT -> FASTORE;
            case DOUBLE -> DASTORE;
            case CHAR -> CASTORE;
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static String primArrayDesc(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> "[B";
            case SHORT -> "[S";
            case INT -> "[I";
            case LONG -> "[J";
            case FLOAT -> "[F";
            case DOUBLE -> "[D";
            case BOOL -> "[Z";
            case CHAR -> "[C";
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static String primViewFactory(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> "ofBytes";
            case SHORT -> "ofShorts";
            case INT -> "ofInts";
            case LONG -> "ofLongs";
            case FLOAT -> "ofFloats";
            case DOUBLE -> "ofDoubles";
            case BOOL -> "ofBooleans";
            case CHAR -> "ofChars";
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }
}
