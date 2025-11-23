package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

class OverwriteHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean = annotationDesc.contains("Overwrite")

    override fun handle(targetClass: ClassNode, mixinClass: ClassNode, sourceMethod: MethodNode, annotation: AnnotationNode) {
        val targetMethod = targetClass.methods.find { it.name == sourceMethod.name && it.desc == sourceMethod.desc } ?: return

        targetMethod.instructions.clear()
        targetMethod.instructions.add(AsmHelper.cloneInstructions(sourceMethod.instructions))

        AsmHelper.remapMemberAccess(targetMethod.instructions, mixinClass.name, targetClass.name)

        targetMethod.visibleAnnotations?.removeIf { it.desc.contains("Overwrite") }
    }
}