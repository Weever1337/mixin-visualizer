package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.CodeGenerationUtils
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

class RedirectHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "Redirect"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val handlerArgs = Type.getArgumentTypes(sourceMethod.desc)
        val handlerStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            for (insn in MixinExtrasSupport.findMatches(targetClass, targetMethod, annotation)) {
                val consumed = MixinExtrasSupport.consumedTypes(insn) ?: continue
                if (consumed.size > handlerArgs.size) continue

                val data = CodeGenerationUtils.prepareCode(
                    sourceMethod,
                    mixinClass.name,
                    targetClass,
                    targetMethod,
                    isRedirect = true
                )

                val slots = IntArray(handlerArgs.size)
                var slot = data.offset + if (handlerStatic) 0 else 1
                for (i in handlerArgs.indices) {
                    slots[i] = slot
                    slot += handlerArgs[i].size
                }

                val code = InsnList()
                for (i in consumed.indices.reversed()) {
                    code.add(VarInsnNode(handlerArgs[i].getOpcode(Opcodes.ISTORE), slots[i]))
                }
                for (i in consumed.size until handlerArgs.size) {
                    AsmHelper.pushArgOrDefault(code, targetMethod, i - consumed.size, handlerArgs[i])
                    code.add(VarInsnNode(handlerArgs[i].getOpcode(Opcodes.ISTORE), slots[i]))
                }
                code.add(data.instructions)

                targetMethod.instructions.insertBefore(insn, code)
                targetMethod.tryCatchBlocks.addAll(data.tryCatchBlocks)
                targetMethod.instructions.remove(insn)
            }
        }
    }
}
