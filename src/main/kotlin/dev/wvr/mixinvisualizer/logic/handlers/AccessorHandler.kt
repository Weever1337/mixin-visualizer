package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.*

class AccessorHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean = annotationDesc.contains("Accessor")

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        var targetFieldName = AnnotationUtils.getValue(annotation, "value") as? String ?: ""

        if (targetFieldName.isEmpty()) {
            val name = sourceMethod.name
            if (name.startsWith("get") || name.startsWith("set")) {
                targetFieldName = name.substring(3).replaceFirstChar { it.lowercase(Locale.getDefault()) }
            } else if (name.startsWith("is")) {
                targetFieldName = name.substring(2).replaceFirstChar { it.lowercase(Locale.getDefault()) }
            }
        }

        if (targetFieldName.isEmpty()) return

        val targetField = targetClass.fields.find { it.name == targetFieldName } ?: return

        val isStatic = (targetField.access and Opcodes.ACC_STATIC) != 0
        val methodArgs = Type.getArgumentTypes(sourceMethod.desc)
        val methodReturn = Type.getReturnType(sourceMethod.desc)

        val isGetter = methodArgs.isEmpty() && methodReturn.sort != Type.VOID
        val isSetter = methodArgs.size == 1 && methodReturn.sort == Type.VOID

        val insns = InsnList()

        if (isGetter) {
            if (!isStatic) {
                insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                insns.add(FieldInsnNode(Opcodes.GETFIELD, targetClass.name, targetField.name, targetField.desc))
            } else {
                insns.add(FieldInsnNode(Opcodes.GETSTATIC, targetClass.name, targetField.name, targetField.desc))
            }

            insns.add(InsnNode(methodReturn.getOpcode(Opcodes.IRETURN)))

        } else if (isSetter) {
            if (!isStatic) {
                insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                insns.add(VarInsnNode(methodArgs[0].getOpcode(Opcodes.ILOAD), 1))
                insns.add(FieldInsnNode(Opcodes.PUTFIELD, targetClass.name, targetField.name, targetField.desc))
            } else {
                insns.add(VarInsnNode(methodArgs[0].getOpcode(Opcodes.ILOAD), 0))
                //val varIndex = if ((sourceMethod.access and Opcodes.ACC_STATIC) != 0) 0 else 1
                insns.add(FieldInsnNode(Opcodes.PUTSTATIC, targetClass.name, targetField.name, targetField.desc))
            }
            insns.add(InsnNode(Opcodes.RETURN))
        }

        val targetMethod = targetClass.methods.find { it.name == sourceMethod.name && it.desc == sourceMethod.desc }
        if (targetMethod != null) {
            targetMethod.instructions.clear()
            targetMethod.tryCatchBlocks.clear()
            targetMethod.instructions.add(insns)
            targetMethod.access = (targetMethod.access and Opcodes.ACC_ABSTRACT.inv()) or Opcodes.ACC_PUBLIC
        }
    }
}