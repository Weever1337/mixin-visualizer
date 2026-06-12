package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.CodeGenerationUtils
import dev.wvr.mixinvisualizer.logic.util.SliceHelper
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
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
        val atTarget = AnnotationUtils.getAtValue(annotation, "target")

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            val matches = SliceHelper.filterBySlice(
                targetClass, targetMethod, annotation,
                targetMethod.instructions.toArray().filter { insn ->
                    (insn is MethodInsnNode && TargetFinderUtils.isMatch(insn, atTarget)) ||
                            (insn is FieldInsnNode && TargetFinderUtils.isMatchField(insn, atTarget))
                }
            )

            for (insn in matches) {
                val injectionData = CodeGenerationUtils.prepareCode(
                    sourceMethod,
                    mixinClass.name,
                    targetClass,
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