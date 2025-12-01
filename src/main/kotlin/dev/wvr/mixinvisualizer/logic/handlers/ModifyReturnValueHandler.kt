package dev.wvr.mixinvisualizer.logic.handlers

import com.demonwav.mcdev.platform.mixin.util.getParameter
import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.CodeGenerationUtils
import dev.wvr.mixinvisualizer.logic.util.SliceHelper
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.objectweb.asm.Type

class ModifyReturnValueHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean = annotationDesc.contains("ModifyReturnValue")

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        var atValue = AnnotationUtils.getAtValue(annotation, "at")

        if (atValue.isEmpty()) atValue = "RETURN"

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            val (startNode, endNode) = SliceHelper.getSliceRange(targetClass, targetMethod, annotation)

            val injectionData = CodeGenerationUtils.prepareCode(
                sourceMethod,
                mixinClass.name,
                targetClass.name,
                targetMethod,
                isRedirect = false
            )
            when (atValue) {
                "TAIL", "RETURN" -> {
                    val iter = targetMethod.instructions.iterator()
                    while (iter.hasNext()) {
                        val insn = iter.next()
                        if (insn.opcode in Opcodes.IRETURN..Opcodes.RETURN) {
                            val lvIndex = targetMethod.maxLocals
                            targetMethod.maxLocals += 1

                            val list = InsnList()

                            //store old returnvalue
                            when (insn.opcode) {
                                Opcodes.IRETURN -> list.add(VarInsnNode(Opcodes.ISTORE, lvIndex))
                                Opcodes.LRETURN -> list.add(VarInsnNode(Opcodes.LSTORE, lvIndex))
                                Opcodes.FRETURN -> list.add(VarInsnNode(Opcodes.FSTORE, lvIndex))
                                Opcodes.DRETURN -> list.add(VarInsnNode(Opcodes.DSTORE, lvIndex))
                                Opcodes.ARETURN -> list.add(VarInsnNode(Opcodes.ASTORE, lvIndex))
                            }

                            //also load this on stack
                            list.add(VarInsnNode(Opcodes.ALOAD, 0))

                            //find returnvalue to be able to assign a variable
                            when (insn.opcode) {
                                Opcodes.IRETURN -> list.add(VarInsnNode(Opcodes.ILOAD, lvIndex))
                                Opcodes.LRETURN -> list.add(VarInsnNode(Opcodes.LLOAD, lvIndex))
                                Opcodes.FRETURN -> list.add(VarInsnNode(Opcodes.FLOAD, lvIndex))
                                Opcodes.DRETURN -> list.add(VarInsnNode(Opcodes.DLOAD, lvIndex))
                                Opcodes.ARETURN -> list.add(VarInsnNode(Opcodes.ALOAD, lvIndex))
                            }

                            //use returnvalue of handler/mixin instead of the original
                            val returnType = Type.getReturnType(targetMethod.desc)
                            val handlerDesc = "(${returnType.descriptor})${returnType.descriptor}"
                            list.add(
                                MethodInsnNode(
                                    Opcodes.INVOKEVIRTUAL,
                                    mixinClass.name.replace('.', '/'), //generate the üath for the handler/mixin class
                                    ref,
                                    handlerDesc,
                                    false
                                )
                            )

                            //return based on type
                            list.add(when (insn.opcode) {
                                Opcodes.IRETURN -> InsnNode(Opcodes.IRETURN)
                                Opcodes.LRETURN -> InsnNode(Opcodes.LRETURN)
                                Opcodes.FRETURN -> InsnNode(Opcodes.FRETURN)
                                Opcodes.DRETURN -> InsnNode(Opcodes.DRETURN)
                                Opcodes.ARETURN -> InsnNode(Opcodes.ARETURN)
                                else -> throw IllegalStateException("Unexpected return opcode ${insn.opcode}")
                            })

                            targetMethod.instructions.insertBefore(insn, list)
                            targetMethod.instructions.remove(insn)
                        }
                    }
                }
            }


        }
    }
}