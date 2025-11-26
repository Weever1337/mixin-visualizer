package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.CodeGenerationUtils
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

class InjectHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean = annotationDesc.contains("Inject")

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
                targetClass.name,
                targetMethod,
                isRedirect = false
            )

            when (atValue) {
                "HEAD" -> {
                    targetMethod.instructions.insert(injectionData.instructions)
                    targetMethod.tryCatchBlocks.addAll(injectionData.tryCatchBlocks)
                }

                "TAIL", "RETURN" -> {
                    val iter = targetMethod.instructions.iterator()
                    while (iter.hasNext()) {
                        val insn = iter.next()
                        if (insn.opcode in Opcodes.IRETURN..Opcodes.RETURN) {
                            val map = HashMap<LabelNode, LabelNode>()
                            val code = AsmHelper.cloneInstructions(injectionData.instructions, map)
                            val tcbs = AsmHelper.cloneTryCatchBlocks(injectionData.tryCatchBlocks, map)

                            targetMethod.instructions.insertBefore(insn, code)
                            targetMethod.tryCatchBlocks.addAll(tcbs)
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
                                val map = HashMap<LabelNode, LabelNode>()
                                val code = AsmHelper.cloneInstructions(injectionData.instructions, map)
                                val tcbs = AsmHelper.cloneTryCatchBlocks(injectionData.tryCatchBlocks, map)

                                if (shift == "AFTER") targetMethod.instructions.insert(insn, code)
                                else targetMethod.instructions.insertBefore(insn, code)

                                targetMethod.tryCatchBlocks.addAll(tcbs)
                            }
                        }
                    }
                }

                "FIELD" -> {
                    if (atTarget.isNotEmpty()) {
                        val shift = AnnotationUtils.getAtValue(annotation, "shift")
                        val opcodeVal = AnnotationUtils.getAtValue(annotation, "opcode")
                        val targetOpcode = opcodeVal.toIntOrNull() ?: -1

                        val iter = targetMethod.instructions.iterator()
                        while (iter.hasNext()) {
                            val insn = iter.next()
                            if (insn is FieldInsnNode && TargetFinderUtils.isMatchField(insn, atTarget)) {
                                if (targetOpcode != -1 && insn.opcode != targetOpcode) continue

                                val map = HashMap<LabelNode, LabelNode>()
                                val code = AsmHelper.cloneInstructions(injectionData.instructions, map)
                                val tcbs = AsmHelper.cloneTryCatchBlocks(injectionData.tryCatchBlocks, map)

                                if (shift == "AFTER") targetMethod.instructions.insert(insn, code)
                                else targetMethod.instructions.insertBefore(insn, code)

                                targetMethod.tryCatchBlocks.addAll(tcbs)
                            }
                        }
                    }
                }

                "NEW" -> {
                    if (atTarget.isNotEmpty()) {
                        val shift = AnnotationUtils.getAtValue(annotation, "shift")
                        val iter = targetMethod.instructions.iterator()
                        while (iter.hasNext()) {
                            val insn = iter.next()
                            if (insn is TypeInsnNode && insn.opcode == Opcodes.NEW) {
                                val normalizedTarget = atTarget.replace('.', '/')
                                if (insn.desc == normalizedTarget) {
                                    val map = HashMap<LabelNode, LabelNode>()
                                    val code = AsmHelper.cloneInstructions(injectionData.instructions, map)
                                    val tcbs = AsmHelper.cloneTryCatchBlocks(injectionData.tryCatchBlocks, map)

                                    if (shift == "AFTER") targetMethod.instructions.insert(insn, code)
                                    else targetMethod.instructions.insertBefore(insn, code)

                                    targetMethod.tryCatchBlocks.addAll(tcbs)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}