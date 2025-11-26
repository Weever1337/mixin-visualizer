package dev.wvr.mixinvisualizer.logic.util

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

object AnnotationUtils {
    fun getListValue(node: AnnotationNode, key: String): List<String> {
        val values = node.values ?: return emptyList()
        for (i in 0 until values.size step 2) {
            if (values[i] == key) {
                val list = values[i + 1]
                if (list is List<*>) return list.map { it.toString() }
            }
        }
        return emptyList()
    }

    fun getAtValue(node: AnnotationNode, subKey: String): String {
        val values = node.values ?: return ""
        for (i in 0 until values.size step 2) {
            if (values[i] == "at") {
                val atNode = values[i + 1] as? AnnotationNode ?: continue
                val atArgs = atNode.values ?: continue
                for (j in 0 until atArgs.size step 2) {
                    if (atArgs[j] == subKey) {
                        val v = atArgs[j + 1]
                        if (v is List<*>) return v.firstOrNull()?.toString() ?: ""
                        if (v is Array<*>) return v.lastOrNull()?.toString() ?: ""
                        return v.toString()
                    }
                }
            }
        }
        return ""
    }
}

object TargetFinderUtils {
    fun findTargetMethodLike(classNode: ClassNode, ref: String): MethodNode? {
        if (ref.contains("(")) {
            val name = ref.substringBefore("(")
            val desc = ref.substringAfter("(")
            return classNode.methods.find { it.name == name && it.desc == desc }
        }
        return classNode.methods.find { it.name == ref }
    }

    fun isMatch(insn: MethodInsnNode, targetRef: String): Boolean {
        var cleanRef = targetRef
        if (cleanRef.contains(";")) cleanRef = cleanRef.substringAfterLast(";")
        if (cleanRef.contains("(")) {
            val name = cleanRef.substringBefore("(")
            val desc = cleanRef.substringAfter("(")
            return insn.name == name && insn.desc == desc
        }
        return insn.name == cleanRef
    }

    fun isMatchField(insn: FieldInsnNode, targetRef: String): Boolean {
        var ref = targetRef

        val colonIndex = ref.lastIndexOf(':')
        var targetDesc: String? = null
        if (colonIndex != -1) {
            targetDesc = ref.substring(colonIndex + 1)
            ref = ref.take(colonIndex)
        }

        var targetOwner: String? = null
        val semiIndex = ref.lastIndexOf(';')
        if (semiIndex != -1) {
            var ownerPart = ref.take(semiIndex)
            if (ownerPart.startsWith("L")) ownerPart = ownerPart.substring(1)
            targetOwner = ownerPart
            ref = ref.substring(semiIndex + 1)
        } else {
            val dotIndex = ref.lastIndexOf('.')
            if (dotIndex != -1) {
                targetOwner = ref.take(dotIndex).replace('.', '/')
                ref = ref.substring(dotIndex + 1)
            }
        }

        val targetName = ref

        if (insn.name != targetName) return false
        if (targetDesc != null && insn.desc != targetDesc) return false
        if (targetOwner != null && insn.owner != targetOwner) return false

        return true
    }
}

data class GeneratedCode(
    val instructions: InsnList,
    val tryCatchBlocks: List<TryCatchBlockNode>
)

object CodeGenerationUtils {
    private const val CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo"
    private const val CALLBACK_INFO_RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"

    fun prepareCode(
        source: MethodNode,
        mixinName: String,
        targetName: String,
        targetMethod: MethodNode,
        isRedirect: Boolean
    ): GeneratedCode {
        val labelMap = HashMap<LabelNode, LabelNode>()
        val code = AsmHelper.cloneInstructions(source.instructions, labelMap)
        val tryCatchBlocks = AsmHelper.cloneTryCatchBlocks(source, labelMap)

        val offset = targetMethod.maxLocals + 5

        val sourceArgs = Type.getArgumentTypes(source.desc)
        val targetArgs = Type.getArgumentTypes(targetMethod.desc)

        if (!isRedirect && sourceArgs.size > targetArgs.size) {
            val stubInit = InsnList()
            val startSlot = AsmHelper.getArgsSize(targetMethod)
            var currentSlotIndex = startSlot
            val startIndex = targetArgs.size

            for (i in startIndex until sourceArgs.size) {
                val type = sourceArgs[i]
                if (type.internalName != CALLBACK_INFO && type.internalName != CALLBACK_INFO_RETURNABLE) {
                    AsmHelper.generateDefaultValue(stubInit, type, currentSlotIndex + offset)
                }
                currentSlotIndex += type.size
            }
            code.insert(stubInit)
        }

        try {
            val ciIndex = findCallbackInfoVarIndex(source)
            processCallbackInfo(code, Type.getReturnType(targetMethod.desc), ciIndex)
        } catch (_: Exception) {
        }

        AsmHelper.remapMemberAccess(code, mixinName, targetName)
        remapLocalVariables(code, source, targetMethod, offset)

        AsmHelper.cleanupReturnInstruction(code, !isRedirect)

        return GeneratedCode(code, tryCatchBlocks)
    }

    private fun findCallbackInfoVarIndex(method: MethodNode): Int {
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        var index = if (isStatic) 0 else 1
        for (arg in Type.getArgumentTypes(method.desc)) {
            if (arg.internalName == CALLBACK_INFO || arg.internalName == CALLBACK_INFO_RETURNABLE) {
                return index
            }
            index += arg.size
        }
        return -1
    }

    private fun processCallbackInfo(insns: InsnList, targetReturnType: Type, ciVarIndex: Int) {
        var node = insns.first
        while (node != null) {
            val next = node.next
            if (node is MethodInsnNode) {
                handleMethodCall(insns, node, targetReturnType, ciVarIndex)
            }
            node = next
        }
    }

    private fun handleMethodCall(insns: InsnList, insn: MethodInsnNode, targetReturnType: Type, ciVarIndex: Int) {
        if (insn.owner == CALLBACK_INFO && insn.name == "cancel" && insn.desc == "()V") {
            removeCiLoadAndCall(insns, insn, ciVarIndex)
            insns.insertBefore(insn, InsnNode(Opcodes.RETURN))
            insns.remove(insn)
        } else if (insn.owner == CALLBACK_INFO_RETURNABLE && insn.name == "setReturnValue") {
            val args = Type.getArgumentTypes(insn.desc)
            if (args.isNotEmpty()) {
                val valueType = args[0]

                val removedLoad = removeCiLoadIfPossible(insns, insn, ciVarIndex)

                if (!removedLoad) {
                    if (valueType.size == 1) {
                        insns.insertBefore(insn, InsnNode(Opcodes.SWAP))
                        insns.insertBefore(insn, InsnNode(Opcodes.POP))
                    } else {
                        insns.insertBefore(insn, InsnNode(Opcodes.DUP2_X1))
                        insns.insertBefore(insn, InsnNode(Opcodes.POP2))
                        insns.insertBefore(insn, InsnNode(Opcodes.POP))
                    }
                }

                adjustType(insns, insn, targetReturnType)
                insns.insertBefore(insn, InsnNode(targetReturnType.getOpcode(Opcodes.IRETURN)))
                insns.remove(insn)
            }
        }
    }

    private fun removeCiLoadAndCall(insns: InsnList, callInsn: AbstractInsnNode, ciIndex: Int) {
        val prev = callInsn.previous
        if (prev is VarInsnNode && prev.opcode == Opcodes.ALOAD && prev.`var` == ciIndex) {
            insns.remove(prev)
        } else {
            insns.insertBefore(callInsn, InsnNode(Opcodes.POP))
        }
    }

    private fun removeCiLoadIfPossible(insns: InsnList, callInsn: AbstractInsnNode, ciIndex: Int): Boolean {
        var current = callInsn.previous
        var steps = 0
        while (current != null && steps < 10) {
            if (current is VarInsnNode && current.opcode == Opcodes.ALOAD && current.`var` == ciIndex) {
                insns.remove(current)
                return true
            }
            steps++
            current = current.previous
        }
        return false
    }

    private fun adjustType(insns: InsnList, location: AbstractInsnNode, targetType: Type) {
        if (targetType.sort != Type.OBJECT && targetType.sort != Type.ARRAY && targetType.sort != Type.VOID) {
            val internalName = getWrapperInternalName(targetType)
            val methodName = getUnboxMethodName(targetType)
            val desc = "()" + targetType.descriptor

            insns.insertBefore(location, TypeInsnNode(Opcodes.CHECKCAST, internalName))
            insns.insertBefore(location, MethodInsnNode(Opcodes.INVOKEVIRTUAL, internalName, methodName, desc, false))
        } else if (targetType.sort == Type.OBJECT || targetType.sort == Type.ARRAY) {
            if (targetType.internalName != "java/lang/Object") {
                insns.insertBefore(location, TypeInsnNode(Opcodes.CHECKCAST, targetType.internalName))
            }
        }
    }

    private fun getWrapperInternalName(type: Type): String {
        return when (type.sort) {
            Type.BOOLEAN -> "java/lang/Boolean"
            Type.CHAR -> "java/lang/Character"
            Type.BYTE -> "java/lang/Byte"
            Type.SHORT -> "java/lang/Short"
            Type.INT -> "java/lang/Integer"
            Type.FLOAT -> "java/lang/Float"
            Type.LONG -> "java/lang/Long"
            Type.DOUBLE -> "java/lang/Double"
            else -> "java/lang/Object"
        }
    }

    private fun getUnboxMethodName(type: Type): String {
        return when (type.sort) {
            Type.BOOLEAN -> "booleanValue"
            Type.CHAR -> "charValue"
            Type.BYTE -> "byteValue"
            Type.SHORT -> "shortValue"
            Type.INT -> "intValue"
            Type.FLOAT -> "floatValue"
            Type.LONG -> "longValue"
            Type.DOUBLE -> "doubleValue"
            else -> "toString"
        }
    }

    private fun remapLocalVariables(insns: InsnList, source: MethodNode, target: MethodNode, offset: Int) {
        val targetArgSlotLimit = AsmHelper.getArgsSize(target)
        val iter = insns.iterator()
        while (iter.hasNext()) {
            val insn = iter.next()
            if (insn is VarInsnNode) {
                if (insn.`var` >= targetArgSlotLimit) {
                    insn.`var` += offset
                }
            } else if (insn is IincInsnNode) {
                if (insn.`var` >= targetArgSlotLimit) {
                    insn.`var` += offset
                }
            }
        }
        target.maxLocals += (source.maxLocals + 20)
    }
}