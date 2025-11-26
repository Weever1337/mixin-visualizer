package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.CodeGenerationUtils
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.tree.*

class RedirectHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean = annotationDesc.contains("Redirect")

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val atTarget = AnnotationUtils.getAtValue(annotation, "target")

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            val iter = targetMethod.instructions.iterator()

            while (iter.hasNext()) {
                val insn = iter.next()
                var match = false

                if (insn is MethodInsnNode && TargetFinderUtils.isMatch(insn, atTarget)) {
                    match = true
                } else if (insn is FieldInsnNode && TargetFinderUtils.isMatchField(insn, atTarget)) {
                    match = true
                }

                if (match) {
                    val injectionData = CodeGenerationUtils.prepareCode(
                        sourceMethod,
                        mixinClass.name,
                        targetClass.name,
                        targetMethod,
                        isRedirect = true
                    )

                    val map = HashMap<LabelNode, LabelNode>()
                    val code = AsmHelper.cloneInstructions(injectionData.instructions, map)
                    val tcbs = AsmHelper.cloneTryCatchBlocks(injectionData.tryCatchBlocks, map)

                    targetMethod.instructions.insertBefore(insn, code)
                    targetMethod.tryCatchBlocks.addAll(tcbs)

                    targetMethod.instructions.remove(insn)
                }
            }
        }
    }
}