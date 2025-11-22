package dev.wvr.mixinvisualizer.logic

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

/**
 * TODO: Handle Shadow members (skip them)
 * TODO: Handle Priority / Conflicts (later mixins override earlier ones)
 * TODO: Handle Super calls (calls to mixin superclass methods)
 * TODO: Handle more @At types (FIELD, NEW, etc)
 * TODO: Handle static initializers properly
 * TODO: Handle generic signatures?
 * TODO: Handle Exceptions?
 * TODO: please do better code structure 😢
 *
 * TODO: Support [MixinExtras](https://github.com/LlamaLad7/MixinExtras), [MixinSquared](https://github.com/Bawnorton/MixinSquared)
 * TODO: more and more...
 */
class MixinTransformer {
    fun transform(target: ClassNode, mixin: ClassNode) {
        if (target.fields.none { it.name == "__mixin_fields_here__" }) {
            target.fields.add(FieldNode(Opcodes.ACC_PUBLIC, "__mixin_fields_here__", "Ljava/lang/String;", null, "Applied: ${mixin.name}"))
        }
        mergeUniqueMembers(target, mixin)
        for (mixinMethod in mixin.methods) {
            val anns = mixinMethod.visibleAnnotations ?: continue
            for (node in anns) {
                val desc = node.desc ?: ""
                if (desc.contains("Inject")) applyInject(target, mixin, mixinMethod, node)
                else if (desc.contains("Overwrite")) applyOverwrite(target, mixinMethod)
                else if (desc.contains("Redirect")) applyRedirect(target, mixin, mixinMethod, node)
            }
        }
    }

    private fun mergeUniqueMembers(target: ClassNode, mixin: ClassNode) {
        for (field in mixin.fields) {
            if (target.fields.none { it.name == field.name && it.desc == field.desc }) {
                val newAccess = (field.access and Opcodes.ACC_PRIVATE.inv()) or Opcodes.ACC_PUBLIC
                target.fields.add(FieldNode(newAccess, field.name, field.desc, field.signature, field.value))
            }
        }
        for (method in mixin.methods) {
            val anns = method.visibleAnnotations ?: emptyList()
            val isHandler = anns.any { it.desc.contains("Inject") || it.desc.contains("Overwrite") || it.desc.contains("Redirect") }
            if (!isHandler && method.name != "<init>" && method.name != "<clinit>") {
                if (target.methods.none { it.name == method.name && it.desc == method.desc }) {
                    val newMethod = MethodNode((method.access and Opcodes.ACC_PRIVATE.inv()) or Opcodes.ACC_PUBLIC, method.name, method.desc, method.signature, method.exceptions?.toTypedArray())
                    method.accept(newMethod)
                    remapMemberAccess(newMethod.instructions, mixin.name, target.name)
                    target.methods.add(newMethod)
                }
            }
        }
    }

    private fun applyOverwrite(target: ClassNode, source: MethodNode) {
        val targetMethod = findMethod(target, source.name, source.desc) ?: return
        targetMethod.instructions.clear()
        targetMethod.instructions.add(cloneInstructions(source.instructions))
        targetMethod.visibleAnnotations?.removeIf { it.desc.contains("Overwrite") }
    }

    private fun applyRedirect(targetClass: ClassNode, mixinClass: ClassNode, sourceMethod: MethodNode, annotation: AnnotationNode) {
        val targets = getAnnotationListValue(annotation, "method")
        val atTarget = getAtAnnotationValue(annotation, "target")
        for (ref in targets) {
            val targetMethod = findTargetMethodLike(targetClass, ref) ?: continue
            val iter = targetMethod.instructions.iterator()
            while (iter.hasNext()) {
                val insn = iter.next()
                if (insn is MethodInsnNode && isMatch(insn, atTarget)) {
                    val injectionCode = prepareCode(sourceMethod, mixinClass.name, targetClass.name, targetMethod, true)
                    targetMethod.instructions.insertBefore(insn, injectionCode)
                    targetMethod.instructions.remove(insn)
                } else if (insn is FieldInsnNode && isMatchField(insn, atTarget)) {
                    val injectionCode = prepareCode(sourceMethod, mixinClass.name, targetClass.name, targetMethod, true)
                    targetMethod.instructions.insertBefore(insn, injectionCode)
                    targetMethod.instructions.remove(insn)
                }
            }
        }
    }

    private fun applyInject(targetClass: ClassNode, mixinClass: ClassNode, sourceMethod: MethodNode, annotation: AnnotationNode) {
        val targets = getAnnotationListValue(annotation, "method")
        var atValue = getAtAnnotationValue(annotation, "value")
        val atTarget = getAtAnnotationValue(annotation, "target")
        if (atValue.isEmpty()) atValue = "HEAD"

        for (ref in targets) {
            val targetMethod = findTargetMethodLike(targetClass, ref) ?: continue
            val injectionCode = prepareCode(sourceMethod, mixinClass.name, targetClass.name, targetMethod, false)

            if (atValue == "HEAD") {
                targetMethod.instructions.insert(injectionCode)
            } else if (atValue == "TAIL" || atValue == "RETURN") {
                val iter = targetMethod.instructions.iterator()
                while (iter.hasNext()) {
                    val insn = iter.next()
                    if (insn.opcode in Opcodes.IRETURN..Opcodes.RETURN) {
                        targetMethod.instructions.insertBefore(insn, cloneInstructions(injectionCode))
                    }
                }
            } else if (atValue == "INVOKE" && atTarget.isNotEmpty()) {
                val shift = getAtAnnotationValue(annotation, "shift")
                val iter = targetMethod.instructions.iterator()
                while (iter.hasNext()) {
                    val insn = iter.next()
                    if (insn is MethodInsnNode && isMatch(insn, atTarget)) {
                        val code = cloneInstructions(injectionCode)
                        if (shift == "AFTER") targetMethod.instructions.insert(insn, code)
                        else targetMethod.instructions.insertBefore(insn, code)
                    }
                }
            }
        }
    }

    private fun prepareCode(source: MethodNode, mixinName: String, targetName: String, targetMethod: MethodNode, isRedirect: Boolean): InsnList {
        val code = cloneInstructions(source.instructions)
        val offset = targetMethod.maxLocals + 5

        val sourceArgs = Type.getArgumentTypes(source.desc)
        val targetArgs = Type.getArgumentTypes(targetMethod.desc)

        if (!isRedirect && sourceArgs.size > targetArgs.size) {
            val stubInit = InsnList()

            val startArgIndex = getArgsSize(targetMethod)
            var currentSlotIndex = startArgIndex
            val startIndex = targetArgs.size

            for (i in startIndex until sourceArgs.size) {
                val type = sourceArgs[i]
                generateDefaultValue(stubInit, type, currentSlotIndex + offset)
                currentSlotIndex += type.size
            }
            code.insert(stubInit)
        }

        remapMemberAccess(code, mixinName, targetName)
        remapLocalVariables(code, source, targetMethod, offset)
        cleanupReturnInstruction(code)

        return code
    }

    private fun generateDefaultValue(list: InsnList, type: Type, varIndex: Int) {
        val opStore = type.getOpcode(Opcodes.ISTORE) // gets correct store (ISTORE, FSTORE, ASTORE...)

        when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                list.add(InsnNode(Opcodes.ICONST_0))
                list.add(VarInsnNode(opStore, varIndex))
            }
            Type.FLOAT -> {
                list.add(InsnNode(Opcodes.FCONST_0))
                list.add(VarInsnNode(opStore, varIndex))
            }
            Type.LONG -> {
                list.add(InsnNode(Opcodes.LCONST_0))
                list.add(VarInsnNode(opStore, varIndex))
            }
            Type.DOUBLE -> {
                list.add(InsnNode(Opcodes.DCONST_0))
                list.add(VarInsnNode(opStore, varIndex))
            }
            else -> {
                list.add(InsnNode(Opcodes.ACONST_NULL))
                list.add(VarInsnNode(opStore, varIndex))
            }
        }
    }

    private fun remapLocalVariables(insns: InsnList, source: MethodNode, target: MethodNode, offset: Int) {
        val targetArgCount = getArgsSize(target)
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

    private fun getArgsSize(method: MethodNode): Int {
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        var size = if (isStatic) 0 else 1
        for (type in Type.getArgumentTypes(method.desc)) size += type.size
        return size
    }

    private fun remapMemberAccess(insns: InsnList, mixinName: String, targetName: String) {
        val iter = insns.iterator()
        while (iter.hasNext()) {
            val insn = iter.next()
            if (insn is FieldInsnNode && insn.owner == mixinName) insn.owner = targetName
            else if (insn is MethodInsnNode && insn.owner == mixinName) insn.owner = targetName
        }
    }

    private fun isMatch(insn: MethodInsnNode, targetRef: String): Boolean {
        var cleanRef = targetRef
        if (cleanRef.contains(";")) cleanRef = cleanRef.substringAfterLast(";")
        if (cleanRef.contains("(")) {
            val name = cleanRef.substringBefore("(")
            val desc = cleanRef.substring(cleanRef.indexOf("("))
            return insn.name == name && insn.desc == desc
        }
        return insn.name == cleanRef
    }

    private fun isMatchField(insn: FieldInsnNode, targetRef: String) = insn.name == targetRef

    private fun findTargetMethodLike(classNode: ClassNode, ref: String): MethodNode? {
        if (ref.contains("(")) {
            val name = ref.substringBefore("(")
            val desc = ref.substring(ref.indexOf("("))
            return classNode.methods.find { it.name == name && it.desc == desc }
        }
        return classNode.methods.find { it.name == ref }
    }

    private fun findMethod(classNode: ClassNode, name: String, desc: String) =
        classNode.methods.find { it.name == name && it.desc == desc }

    private fun getAnnotationListValue(node: AnnotationNode, key: String): List<String> {
        val values = node.values ?: return emptyList()
        for (i in 0 until values.size step 2) {
            if (values[i] == key) {
                val list = values[i+1]
                if (list is List<*>) return list.map { it.toString() }
            }
        }
        return emptyList()
    }

    private fun getAtAnnotationValue(node: AnnotationNode, subKey: String): String {
        val values = node.values ?: return ""
        for (i in 0 until values.size step 2) {
            if (values[i] == "at") {
                val atNode = values[i+1] as? AnnotationNode ?: continue
                val atArgs = atNode.values ?: continue
                for (j in 0 until atArgs.size step 2) {
                    if (atArgs[j] == subKey) {
                        val v = atArgs[j+1]
                        if (v is List<*>) return v.firstOrNull()?.toString() ?: ""
                        if (v is Array<*>) return v.lastOrNull()?.toString() ?: ""
                        return v.toString()
                    }
                }
            }
        }
        return ""
    }

    private fun cloneInstructions(list: InsnList): InsnList {
        val clone = InsnList()
        val map = mutableMapOf<LabelNode, LabelNode>()
        var p = list.first
        while (p != null) { if (p is LabelNode) map[p] = LabelNode(); p = p.next }
        p = list.first
        while (p != null) { clone.add(p.clone(map)); p = p.next }
        return clone
    }

    private fun cleanupReturnInstruction(list: InsnList) {
        val endLabel = LabelNode()
        list.add(endLabel)
        var last = list.first
        while (last != null) {
            if (last.opcode in Opcodes.IRETURN..Opcodes.RETURN) list.set(last, JumpInsnNode(Opcodes.GOTO, endLabel))
            last = last.next
        }
    }
}