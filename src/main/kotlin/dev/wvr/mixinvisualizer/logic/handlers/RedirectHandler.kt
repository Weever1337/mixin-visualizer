package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.util.*
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class RedirectHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean = annotationDesc.contains("Redirect")

    override fun handle(targetClass: ClassNode, mixinClass: ClassNode, sourceMethod: MethodNode, annotation: AnnotationNode) {
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
                    val injectionCode = CodeGenerationUtils.prepareCode(sourceMethod, mixinClass.name, targetClass.name, targetMethod, isRedirect = true)
                    targetMethod.instructions.insertBefore(insn, injectionCode)
                    targetMethod.instructions.remove(insn)
                }
            }
        }
    }
}