package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.CodeGenerationUtils
import dev.wvr.mixinvisualizer.logic.util.SliceHelper
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
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

        val shift = AnnotationUtils.getAtValue(annotation, "shift")
        val ordinal = AnnotationUtils.getAtValue(annotation, "ordinal").toIntOrNull() ?: -1
        val captureLocals = ((AnnotationUtils.getValue(annotation, "locals") as? Array<*>)?.getOrNull(1) as? String)
            ?.startsWith("CAPTURE") == true

        val insertAfter = atValue == "INVOKE_ASSIGN" ||
                (shift == "AFTER" && (atValue == "INVOKE" || atValue == "FIELD" || atValue == "NEW"))

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            val insns = targetMethod.instructions.toArray()

            var points: List<AbstractInsnNode> = when (atValue) {
                "HEAD" -> listOfNotNull(AsmHelper.headInsn(targetClass, targetMethod))

                "RETURN" -> insns.filter { it.opcode in Opcodes.IRETURN..Opcodes.RETURN }

                "TAIL" -> listOfNotNull(insns.lastOrNull { it.opcode in Opcodes.IRETURN..Opcodes.RETURN })

                "INVOKE" -> if (atTarget.isEmpty()) emptyList() else insns
                    .filter { it is MethodInsnNode && TargetFinderUtils.isMatch(it, atTarget) }

                "INVOKE_ASSIGN" -> if (atTarget.isEmpty()) emptyList() else insns
                    .filter { it is MethodInsnNode && TargetFinderUtils.isMatch(it, atTarget) && Type.getReturnType(it.desc).sort != Type.VOID }

                "FIELD" -> if (atTarget.isEmpty()) emptyList() else {
                    val targetOpcode = AnnotationUtils.getAtValue(annotation, "opcode").toIntOrNull() ?: -1
                    insns.filter {
                        it is FieldInsnNode && TargetFinderUtils.isMatchField(it, atTarget) &&
                                (targetOpcode == -1 || it.opcode == targetOpcode)
                    }
                }

                "NEW" -> if (atTarget.isEmpty()) emptyList() else {
                    val normalizedTarget = atTarget.replace('.', '/')
                    insns.filter { it is TypeInsnNode && it.opcode == Opcodes.NEW && it.desc == normalizedTarget }
                }

                else -> emptyList()
            }

            if (atValue != "HEAD" && atValue != "TAIL") {
                points = SliceHelper.filterBySlice(targetClass, targetMethod, annotation, points)
                if (ordinal >= 0) points = listOfNotNull(points.getOrNull(ordinal))
            }

            if (atValue == "INVOKE_ASSIGN") points = points.map { assignedStore(it) ?: it }

            val captureReturn = (atValue == "RETURN" || atValue == "TAIL") &&
                    Type.getReturnType(targetMethod.desc).sort != Type.VOID

            for (point in points) {
                val data = CodeGenerationUtils.prepareCode(
                    sourceMethod,
                    mixinClass.name,
                    targetClass,
                    targetMethod,
                    isRedirect = false,
                    captureReturn = captureReturn,
                    capturedLocals = if (captureLocals) localsAt(targetMethod, point, insertAfter) else emptyList()
                )

                if (insertAfter) targetMethod.instructions.insert(point, data.instructions)
                else targetMethod.instructions.insertBefore(point, data.instructions)
                targetMethod.tryCatchBlocks.addAll(data.tryCatchBlocks)
            }
        }
    }

    private fun assignedStore(invoke: AbstractInsnNode): AbstractInsnNode? {
        var next = invoke.next
        while (next != null && next.opcode == -1) next = next.next
        return if (next is VarInsnNode && next.opcode in Opcodes.ISTORE..Opcodes.ASTORE) next else null
    }

    private fun localsAt(method: MethodNode, point: AbstractInsnNode, after: Boolean): List<LocalVariableNode> {
        val insns = method.instructions
        val pos = insns.indexOf(point) + if (after) 1 else 0
        val argsSize = AsmHelper.getArgsSize(method)
        return method.localVariables.orEmpty()
            .filter { it.index >= argsSize && insns.indexOf(it.start) <= pos && pos < insns.indexOf(it.end) }
            .sortedBy { it.index }
            .distinctBy { it.index }
    }
}
