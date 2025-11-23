package dev.wvr.mixinvisualizer.logic.util

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
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
            val desc = ref.substring(ref.indexOf("("))
            return classNode.methods.find { it.name == name && it.desc == desc }
        }
        return classNode.methods.find { it.name == ref }
    }

    fun isMatch(insn: MethodInsnNode, targetRef: String): Boolean {
        var cleanRef = targetRef
        if (cleanRef.contains(";")) cleanRef = cleanRef.substringAfterLast(";")
        if (cleanRef.contains("(")) {
            val name = cleanRef.substringBefore("(")
            val desc = cleanRef.substring(cleanRef.indexOf("("))
            return insn.name == name && insn.desc == desc
        }
        return insn.name == cleanRef
    }

    fun isMatchField(insn: FieldInsnNode, targetRef: String) = insn.name == targetRef
}

object CodeGenerationUtils {
    fun prepareCode(source: MethodNode, mixinName: String, targetName: String, targetMethod: MethodNode, isRedirect: Boolean): InsnList {
        val code = AsmHelper.cloneInstructions(source.instructions)
        val offset = targetMethod.maxLocals + 5

        val sourceArgs = Type.getArgumentTypes(source.desc)
        val targetArgs = Type.getArgumentTypes(targetMethod.desc)

        if (!isRedirect && sourceArgs.size > targetArgs.size) {
            val stubInit = InsnList()
            val startArgIndex = AsmHelper.getArgsSize(targetMethod)
            var currentSlotIndex = startArgIndex
            val startIndex = targetArgs.size

            for (i in startIndex until sourceArgs.size) {
                val type = sourceArgs[i]
                AsmHelper.generateDefaultValue(stubInit, type, currentSlotIndex + offset)
                currentSlotIndex += type.size
            }
            code.insert(stubInit)
        }

        AsmHelper.remapMemberAccess(code, mixinName, targetName)
        remapLocalVariables(code, source, targetMethod, offset)
        AsmHelper.cleanupReturnInstruction(code)

        return code
    }

    private fun remapLocalVariables(insns: InsnList, source: MethodNode, target: MethodNode, offset: Int) {
        val targetArgCount = AsmHelper.getArgsSize(target)
        val iter = insns.iterator()
        while (iter.hasNext()) {
            val insn = iter.next()
            if (insn is VarInsnNode) {
                if (insn.`var` >= targetArgCount) {
                    insn.`var` += offset
                }
            } else if (insn is IincInsnNode) {
                if (insn.`var` >= targetArgCount) {
                    insn.`var` += offset
                }
            }
        }
        target.maxLocals += (source.maxLocals + 20)
    }
}