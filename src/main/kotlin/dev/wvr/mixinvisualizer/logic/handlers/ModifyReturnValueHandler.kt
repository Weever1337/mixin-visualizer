package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

class ModifyReturnValueHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyReturnValue"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            val returnType = Type.getReturnType(targetMethod.desc)
            if (returnType.sort == Type.VOID) continue

            val returns = targetMethod.instructions.toArray()
                .filter { it.opcode in Opcodes.IRETURN..Opcodes.ARETURN }

            for (insn in returns) {
                val list = InsnList()

                if (!isStatic) {
                    val slots = AsmHelper.stashStack(list, listOf(returnType), targetMethod)
                    list.add(VarInsnNode(Opcodes.ALOAD, 0))
                    AsmHelper.unstashStack(list, listOf(returnType), slots)
                }

                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, 1, targetClass, targetMethod)
                list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

                targetMethod.instructions.insertBefore(insn, list)
            }
        }
    }
}
