package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.CodeGenerationUtils
import dev.wvr.mixinvisualizer.logic.util.SliceHelper
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

class InjectHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "Inject"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        var atValue = AnnotationUtils.getAtValue(annotation, "value")
        val atTarget = AnnotationUtils.getAtValue(annotation, "target")

        if (atValue.isEmpty()) atValue = "HEAD"

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            val injectionData = CodeGenerationUtils.prepareCode(
                sourceMethod,
                mixinClass.name,
                targetClass,
                targetMethod,
                isRedirect = false
            )

            val shift = AnnotationUtils.getAtValue(annotation, "shift")
            val insertAfter = shift == "AFTER"
            val ordinal = AnnotationUtils.getAtValue(annotation, "ordinal").toIntOrNull() ?: -1

            var points: List<AbstractInsnNode> = when (atValue) {
                "HEAD" -> {
                    val first = if (targetMethod.name == "<init>") {
                        AsmHelper.findSuperCall(targetMethod, targetClass.superName)?.next
                            ?: targetMethod.instructions.first
                    } else {
                        targetMethod.instructions.first
                    }
                    listOfNotNull(first)
                }

                "RETURN" -> targetMethod.instructions.toArray()
                    .filter { it.opcode in Opcodes.IRETURN..Opcodes.RETURN }

                "TAIL" -> {
                    var insn = targetMethod.instructions.last
                    while (insn != null && insn.opcode !in Opcodes.IRETURN..Opcodes.RETURN) {
                        insn = insn.previous
                    }
                    listOfNotNull(insn)
                }

                "INVOKE" -> if (atTarget.isEmpty()) emptyList() else targetMethod.instructions.toArray()
                    .filter { it is MethodInsnNode && TargetFinderUtils.isMatch(it, atTarget) }

                "FIELD" -> if (atTarget.isEmpty()) emptyList() else {
                    val targetOpcode = AnnotationUtils.getAtValue(annotation, "opcode").toIntOrNull() ?: -1
                    targetMethod.instructions.toArray().filter {
                        it is FieldInsnNode && TargetFinderUtils.isMatchField(it, atTarget) &&
                                (targetOpcode == -1 || it.opcode == targetOpcode)
                    }
                }

                "NEW" -> if (atTarget.isEmpty()) emptyList() else {
                    val normalizedTarget = atTarget.replace('.', '/')
                    targetMethod.instructions.toArray()
                        .filter { it is TypeInsnNode && it.opcode == Opcodes.NEW && it.desc == normalizedTarget }
                }

                else -> emptyList()
            }

            if (atValue == "RETURN" || atValue == "INVOKE" || atValue == "FIELD" || atValue == "NEW") {
                points = SliceHelper.filterBySlice(targetClass, targetMethod, annotation, points)
            }

            if (ordinal >= 0 && atValue != "HEAD" && atValue != "TAIL") {
                points = listOfNotNull(points.getOrNull(ordinal))
            }

            for (insn in points) {
                val map = HashMap<LabelNode, LabelNode>()
                val code = AsmHelper.cloneInstructions(injectionData.instructions, map)
                val tcbs = AsmHelper.cloneTryCatchBlocks(injectionData.tryCatchBlocks, map)

                val afterAllowed = atValue == "INVOKE" || atValue == "FIELD" || atValue == "NEW"
                if (afterAllowed && insertAfter) {
                    targetMethod.instructions.insert(insn, code)
                } else {
                    targetMethod.instructions.insertBefore(insn, code)
                }
                targetMethod.tryCatchBlocks.addAll(tcbs)
            }
        }
    }
}
