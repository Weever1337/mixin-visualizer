package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.*

class InvokerHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean = annotationDesc.contains("Invoker")

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        var targetMethodName = AnnotationUtils.getValue(annotation, "value") as? String ?: ""

        if (targetMethodName.isEmpty()) {
            val name = sourceMethod.name
            if (name.startsWith("call") || name.startsWith("invoke")) {
                val prefix = if (name.startsWith("call")) 4 else 6
                targetMethodName = name.substring(prefix).replaceFirstChar { it.lowercase(Locale.getDefault()) }
            } else {
                targetMethodName = name
            }
        }

        if (targetMethodName.isEmpty()) return

        val targetMethodNode = targetClass.methods.find { it.name == targetMethodName && it.desc == sourceMethod.desc }
            ?: return

        val isStatic = (targetMethodNode.access and Opcodes.ACC_STATIC) != 0
        val returnType = Type.getReturnType(sourceMethod.desc)
        val argTypes = Type.getArgumentTypes(sourceMethod.desc)

        val insns = InsnList()

        if (!isStatic) {
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
        }

        var varIndex = if ((sourceMethod.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        for (arg in argTypes) {
            insns.add(VarInsnNode(arg.getOpcode(Opcodes.ILOAD), varIndex))
            varIndex += arg.size
        }

        val opcode = if (isStatic) Opcodes.INVOKESTATIC
        else if (targetMethodName == "<init>") Opcodes.INVOKESPECIAL
        else if ((targetMethodNode.access and Opcodes.ACC_PRIVATE) != 0) Opcodes.INVOKESPECIAL
        else Opcodes.INVOKEVIRTUAL

        insns.add(MethodInsnNode(opcode, targetClass.name, targetMethodName, sourceMethod.desc, false))

        insns.add(InsnNode(returnType.getOpcode(Opcodes.IRETURN)))

        val wrapperMethod = targetClass.methods.find { it.name == sourceMethod.name && it.desc == sourceMethod.desc }
        if (wrapperMethod != null) {
            wrapperMethod.instructions.clear()
            wrapperMethod.tryCatchBlocks.clear()
            wrapperMethod.instructions.add(insns)
            wrapperMethod.access = (wrapperMethod.access and Opcodes.ACC_ABSTRACT.inv()) or Opcodes.ACC_PUBLIC
        }
    }
}