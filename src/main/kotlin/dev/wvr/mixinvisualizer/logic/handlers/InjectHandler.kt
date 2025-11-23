package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

class InjectHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean = annotationDesc.contains("Inject")

    override fun handle(targetClass: ClassNode, mixinClass: ClassNode, sourceMethod: MethodNode, annotation: AnnotationNode) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        var atValue = AnnotationUtils.getAtValue(annotation, "value")
        val atTarget = AnnotationUtils.getAtValue(annotation, "target")

        if (atValue.isEmpty()) atValue = "HEAD"

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            val injectionCode = CodeGenerationUtils.prepareCode(sourceMethod, mixinClass.name, targetClass.name, targetMethod, isRedirect = false)

            when (atValue) {
                "HEAD" -> {
                    targetMethod.instructions.insert(injectionCode)
                }
                "TAIL", "RETURN" -> {
                    val iter = targetMethod.instructions.iterator()
                    while (iter.hasNext()) {
                        val insn = iter.next()
                        if (insn.opcode in Opcodes.IRETURN..Opcodes.RETURN) {
                            targetMethod.instructions.insertBefore(insn, AsmHelper.cloneInstructions(injectionCode))
                        }
                    }
                }
                "INVOKE" -> {
                    if (atTarget.isNotEmpty()) {
                        val shift = AnnotationUtils.getAtValue(annotation, "shift")
                        val iter = targetMethod.instructions.iterator()
                        while (iter.hasNext()) {
                            val insn = iter.next()
                            if (insn is MethodInsnNode && TargetFinderUtils.isMatch(insn, atTarget)) {
                                val code = AsmHelper.cloneInstructions(injectionCode)
                                if (shift == "AFTER") targetMethod.instructions.insert(insn, code)
                                else targetMethod.instructions.insertBefore(insn, code)
                            }
                        }
                    }
                }
            }
        }
    }
}