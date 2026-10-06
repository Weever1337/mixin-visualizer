package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.CodeGenerationUtils
import dev.wvr.mixinvisualizer.logic.util.InjectionPoints
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
        val atValue = AnnotationUtils.getAtValue(annotation, "value")
        val shift = AnnotationUtils.getAtValue(annotation, "shift")
        val by = AnnotationUtils.getAtValue(annotation, "by").toIntOrNull() ?: 0
        val captureLocals = ((AnnotationUtils.getValue(annotation, "locals") as? Array<*>)?.getOrNull(1) as? String)
            ?.startsWith("CAPTURE") == true

        val insertAfter = atValue == "INVOKE_ASSIGN" ||
                (shift == "AFTER" && atValue != "HEAD" && atValue != "RETURN" && atValue != "TAIL")

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            var points = InjectionPoints.find(targetClass, targetMethod, annotation)
            if (atValue == "INVOKE_ASSIGN") points = points.map { assignedStore(it) ?: it }
            if (shift == "BY") points = points.map { shiftBy(it, by) }

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

    private fun shiftBy(insn: AbstractInsnNode, by: Int): AbstractInsnNode {
        var p = insn
        repeat(kotlin.math.abs(by)) {
            var q = if (by > 0) p.next else p.previous
            while (q != null && q.opcode == -1) q = if (by > 0) q.next else q.previous
            p = q ?: return p
        }
        return p
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
