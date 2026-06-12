package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.SliceHelper
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

class ModifyConstantHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyConstant"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val copiedMethodName = copyMethodToTarget(targetClass, mixinClass, sourceMethod)

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            val (start, end) = SliceHelper.getSliceRange(targetClass, targetMethod, annotation)

            val iter = targetMethod.instructions.iterator()
            var started = (start == null)

            while (iter.hasNext()) {
                val insn = iter.next()
                if (insn == start) started = true
                if (insn == end) break
                if (!started) continue

                if (isConstant(insn)) {
                    val returnType = Type.getReturnType(sourceMethod.desc)
                    if (isConstantTypeMatch(insn, returnType)) {
                        val call = MethodInsnNode(
                            if ((sourceMethod.access and Opcodes.ACC_STATIC) != 0) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                            targetClass.name,
                            copiedMethodName,
                            sourceMethod.desc,
                            false
                        )

                        val list = InsnList()
                        if ((sourceMethod.access and Opcodes.ACC_STATIC) == 0) {
                            list.add(VarInsnNode(Opcodes.ALOAD, 0))
                            list.add(InsnNode(Opcodes.SWAP))
                        }
                        list.add(call)

                        targetMethod.instructions.insert(insn, list)
                        targetMethod.instructions.remove(insn)
                    }
                }
            }
        }
    }

    private fun isConstant(node: AbstractInsnNode): Boolean {
        return node is LdcInsnNode ||
                (node.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5) ||
                (node.opcode in Opcodes.LCONST_0..Opcodes.LCONST_1) ||
                (node.opcode in Opcodes.FCONST_0..Opcodes.FCONST_2) ||
                (node.opcode in Opcodes.DCONST_0..Opcodes.DCONST_1) ||
                node.opcode == Opcodes.BIPUSH || node.opcode == Opcodes.SIPUSH
    }

    private fun isConstantTypeMatch(node: AbstractInsnNode, type: Type): Boolean {
        if (type == Type.INT_TYPE || type == Type.BYTE_TYPE || type == Type.SHORT_TYPE) {
            return node.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 || node.opcode == Opcodes.BIPUSH || node.opcode == Opcodes.SIPUSH || (node is LdcInsnNode && node.cst is Int)
        }
        if (type == Type.FLOAT_TYPE) return node.opcode in Opcodes.FCONST_0..Opcodes.FCONST_2 || (node is LdcInsnNode && node.cst is Float)
        if (type == Type.LONG_TYPE) return node.opcode in Opcodes.LCONST_0..Opcodes.LCONST_1 || (node is LdcInsnNode && node.cst is Long)
        if (type == Type.DOUBLE_TYPE) return node.opcode in Opcodes.DCONST_0..Opcodes.DCONST_1 || (node is LdcInsnNode && node.cst is Double)
        if (type == Type.getType(String::class.java)) return node is LdcInsnNode && node.cst is String
        if (type.sort == Type.OBJECT && node is LdcInsnNode && node.cst is Type) return true
        return false
    }
}

class ModifyVariableHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyVariable"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val atValue = AnnotationUtils.getAtValue(annotation, "value")
        val ordinal = AnnotationUtils.getValue(annotation, "ordinal") as? Int ?: -1

        val copiedMethodName = copyMethodToTarget(targetClass, mixinClass, sourceMethod)

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            val (start, end) = SliceHelper.getSliceRange(targetClass, targetMethod, annotation)

            val targetVarType = Type.getReturnType(sourceMethod.desc)

            val matches = mutableListOf<VarInsnNode>()
            var currentOrdinal = 0
            var started = (start == null)
            for (insn in targetMethod.instructions) {
                if (insn == start) started = true
                if (insn == end) break
                if (!started) continue

                val isStore = (atValue == "STORE" && isStoreOpcode(insn.opcode))
                val isLoad = (atValue == "LOAD" && isLoadOpcode(insn.opcode))
                if (isStore || isLoad) {
                    if (ordinal == -1 || currentOrdinal == ordinal) {
                        matches.add(insn as VarInsnNode)
                        if (ordinal != -1) break
                    }
                    currentOrdinal++
                }
            }

            for (varInsn in matches) {
                val list = InsnList()
                val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

                list.add(VarInsnNode(targetVarType.getOpcode(Opcodes.ILOAD), varInsn.`var`))

                if (!isStatic) {
                    list.add(VarInsnNode(Opcodes.ALOAD, 0))
                    list.add(InsnNode(Opcodes.SWAP))
                }

                list.add(
                    MethodInsnNode(
                        if (isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                        targetClass.name,
                        copiedMethodName,
                        sourceMethod.desc,
                        false
                    )
                )

                list.add(VarInsnNode(targetVarType.getOpcode(Opcodes.ISTORE), varInsn.`var`))

                if (atValue == "STORE") {
                    targetMethod.instructions.insert(varInsn, list)
                } else {
                    targetMethod.instructions.insertBefore(varInsn, list)
                }
            }
        }
    }

    private fun isStoreOpcode(op: Int) = op in Opcodes.ISTORE..Opcodes.ASTORE
    private fun isLoadOpcode(op: Int) = op in Opcodes.ILOAD..Opcodes.ALOAD
}

class ModifyArgHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyArg"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val atTarget = AnnotationUtils.getAtValue(annotation, "target")
        val explicitIndex = AnnotationUtils.getValue(annotation, "index") as? Int ?: -1
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0
        val handlerValueType = Type.getReturnType(sourceMethod.desc)

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            val matches = targetMethod.instructions.toArray()
                .filterIsInstance<MethodInsnNode>()
                .filter { TargetFinderUtils.isMatch(it, atTarget) }

            for (insn in matches) {
                val argTypes = Type.getArgumentTypes(insn.desc)

                val index = if (explicitIndex >= 0) explicitIndex
                else argTypes.indexOfFirst { it == handlerValueType }
                if (index !in argTypes.indices) continue

                val tailTypes = argTypes.drop(index + 1)
                val valueType = argTypes[index]

                val list = InsnList()
                val tailSlots = AsmHelper.stashStack(list, tailTypes, targetMethod)

                if (!isStatic) {
                    val valueSlots = AsmHelper.stashStack(list, listOf(valueType), targetMethod)
                    list.add(VarInsnNode(Opcodes.ALOAD, 0))
                    AsmHelper.unstashStack(list, listOf(valueType), valueSlots)
                }
                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, 1, targetClass, targetMethod)
                list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

                AsmHelper.unstashStack(list, tailTypes, tailSlots)

                targetMethod.instructions.insertBefore(insn, list)
            }
        }
    }
}

private fun copyMethodToTarget(targetClass: ClassNode, mixinClass: ClassNode, sourceMethod: MethodNode): String {
    val existing = targetClass.methods.find { it.name == sourceMethod.name && it.desc == sourceMethod.desc }
    if (existing != null) return existing.name

    val newName = sourceMethod.name + "\$visualized" + (System.nanoTime() % 10000)
    val newMethod = MethodNode(
        (sourceMethod.access and Opcodes.ACC_PRIVATE.inv()) or Opcodes.ACC_PUBLIC,
        newName,
        sourceMethod.desc,
        sourceMethod.signature,
        sourceMethod.exceptions?.toTypedArray()
    )
    sourceMethod.accept(newMethod)
    AsmHelper.remapMemberAccess(newMethod.instructions, mixinClass.name, targetClass.name)
    AsmHelper.stripMixinAnnotations(newMethod)
    targetClass.methods.add(newMethod)
    return newName
}