package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.LocalsSupport
import dev.wvr.mixinvisualizer.logic.util.SliceHelper
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

internal object MixinExtrasSupport {
    fun invokeHandler(targetClass: ClassNode, source: MethodNode): MethodInsnNode {
        val isStatic = (source.access and Opcodes.ACC_STATIC) != 0
        return MethodInsnNode(
            if (isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
            targetClass.name,
            source.name,
            source.desc,
            false
        )
    }

    fun pushExtraArgs(
        list: InsnList,
        source: MethodNode,
        consumedArgs: Int,
        targetClass: ClassNode,
        targetMethod: MethodNode
    ) {
        val args = Type.getArgumentTypes(source.desc)
        for (i in consumedArgs until args.size) {
            LocalsSupport.pushExtraArg(list, source, i, args[i], targetClass, targetMethod)
        }
    }

    fun consumedTypes(insn: AbstractInsnNode): List<Type>? = when (insn) {
        is MethodInsnNode -> {
            val types = mutableListOf<Type>()
            if (insn.opcode != Opcodes.INVOKESTATIC) types.add(Type.getObjectType(insn.owner))
            types.addAll(Type.getArgumentTypes(insn.desc))
            types
        }
        is FieldInsnNode -> when (insn.opcode) {
            Opcodes.PUTFIELD -> listOf(Type.getObjectType(insn.owner), Type.getType(insn.desc))
            Opcodes.PUTSTATIC -> listOf(Type.getType(insn.desc))
            Opcodes.GETFIELD -> listOf(Type.getObjectType(insn.owner))
            Opcodes.GETSTATIC -> emptyList()
            else -> null
        }
        else -> null
    }

    fun producedType(insn: AbstractInsnNode): Type = when (insn) {
        is MethodInsnNode -> Type.getReturnType(insn.desc)
        is FieldInsnNode -> when (insn.opcode) {
            Opcodes.GETFIELD, Opcodes.GETSTATIC -> Type.getType(insn.desc)
            else -> Type.VOID_TYPE
        }
        else -> Type.VOID_TYPE
    }

    fun findMatches(
        targetClass: ClassNode,
        targetMethod: MethodNode,
        annotation: AnnotationNode
    ): List<AbstractInsnNode> {
        val atTarget = AnnotationUtils.getAtValue(annotation, "target")
        if (atTarget.isEmpty()) return emptyList()

        val opcode = AnnotationUtils.getAtValue(annotation, "opcode").toIntOrNull() ?: -1
        var all = targetMethod.instructions.toArray().filter { insn ->
            (insn is MethodInsnNode && TargetFinderUtils.isMatch(insn, atTarget)) ||
                    (insn is FieldInsnNode && TargetFinderUtils.isMatchField(insn, atTarget) && (opcode == -1 || insn.opcode == opcode))
        }

        all = SliceHelper.filterBySlice(targetClass, targetMethod, annotation, all)

        val ordinal = AnnotationUtils.getAtValue(annotation, "ordinal").toIntOrNull() ?: -1
        return if (ordinal >= 0) listOfNotNull(all.getOrNull(ordinal)) else all
    }
}

class ModifyExpressionValueHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyExpressionValue"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            for (insn in MixinExtrasSupport.findMatches(targetClass, targetMethod, annotation)) {
                val valueType = MixinExtrasSupport.producedType(insn)
                if (valueType.sort == Type.VOID) continue

                val list = InsnList()
                if (!isStatic) {
                    val slots = AsmHelper.stashStack(list, listOf(valueType), targetMethod)
                    list.add(VarInsnNode(Opcodes.ALOAD, 0))
                    AsmHelper.unstashStack(list, listOf(valueType), slots)
                }
                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, 1, targetClass, targetMethod)
                list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

                targetMethod.instructions.insert(insn, list)
            }
        }
    }
}

class ModifyReceiverHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyReceiver"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            val matches = MixinExtrasSupport.findMatches(targetClass, targetMethod, annotation)
                .filterIsInstance<MethodInsnNode>()
                .filter { it.opcode != Opcodes.INVOKESTATIC }

            for (insn in matches) {
                val receiverType = Type.getObjectType(insn.owner)
                val argTypes = Type.getArgumentTypes(insn.desc).toList()

                val list = InsnList()
                val argSlots = AsmHelper.stashStack(list, argTypes, targetMethod)

                if (!isStatic) {
                    val recvSlots = AsmHelper.stashStack(list, listOf(receiverType), targetMethod)
                    list.add(VarInsnNode(Opcodes.ALOAD, 0))
                    AsmHelper.unstashStack(list, listOf(receiverType), recvSlots)
                }
                AsmHelper.unstashStack(list, argTypes, argSlots)
                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, 1 + argTypes.size, targetClass, targetMethod)
                list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

                AsmHelper.unstashStack(list, argTypes, argSlots)

                targetMethod.instructions.insertBefore(insn, list)
            }
        }
    }
}

class WrapOperationHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "WrapOperation"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            for (insn in MixinExtrasSupport.findMatches(targetClass, targetMethod, annotation)) {
                val consumed = MixinExtrasSupport.consumedTypes(insn) ?: continue

                val list = InsnList()

                if (!isStatic) {
                    val slots = AsmHelper.stashStack(list, consumed, targetMethod)
                    list.add(VarInsnNode(Opcodes.ALOAD, 0))
                    AsmHelper.unstashStack(list, consumed, slots)
                }

                list.add(InsnNode(Opcodes.ACONST_NULL)) // TODO (wrapop) real Operation lambda instead of null
                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, consumed.size + 1, targetClass, targetMethod)
                list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

                val originalType = MixinExtrasSupport.producedType(insn)
                val handlerType = Type.getReturnType(sourceMethod.desc)
                if (originalType.sort == Type.VOID && handlerType.sort != Type.VOID) {
                    list.add(InsnNode(if (handlerType.size == 2) Opcodes.POP2 else Opcodes.POP))
                }

                targetMethod.instructions.insertBefore(insn, list)
                targetMethod.instructions.remove(insn)
            }
        }
    }
}

class WrapMethodHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "WrapMethod"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val handlerStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            if (targetMethod.instructions.size() == 0) continue

            val origName = targetMethod.name + "\$original"
            if (targetClass.methods.none { it.name == origName && it.desc == targetMethod.desc }) {
                val orig = MethodNode(
                    (targetMethod.access and Opcodes.ACC_PUBLIC.inv() and Opcodes.ACC_PROTECTED.inv()) or Opcodes.ACC_PRIVATE,
                    origName,
                    targetMethod.desc,
                    targetMethod.signature,
                    targetMethod.exceptions?.toTypedArray()
                )
                targetMethod.accept(orig)
                orig.name = origName
                orig.access = (orig.access and Opcodes.ACC_PUBLIC.inv() and Opcodes.ACC_PROTECTED.inv()) or Opcodes.ACC_PRIVATE
                AsmHelper.stripMixinAnnotations(orig)
                targetClass.methods.add(orig)
            }

            targetMethod.instructions.clear()
            targetMethod.tryCatchBlocks?.clear()
            targetMethod.localVariables = ArrayList()

            val targetStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
            val argTypes = Type.getArgumentTypes(targetMethod.desc)

            val insns = InsnList()
            if (!handlerStatic) insns.add(VarInsnNode(Opcodes.ALOAD, 0))

            var slot = if (targetStatic) 0 else 1
            for (arg in argTypes) {
                insns.add(VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot))
                slot += arg.size
            }

            insns.add(InsnNode(Opcodes.ACONST_NULL)) // TODO (wrapop) real Operation lambda instead of null
            MixinExtrasSupport.pushExtraArgs(insns, sourceMethod, argTypes.size + 1, targetClass, targetMethod)
            insns.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

            val returnType = Type.getReturnType(targetMethod.desc)
            insns.add(InsnNode(returnType.getOpcode(Opcodes.IRETURN)))

            targetMethod.instructions.add(insns)
        }
    }
}

class WrapWithConditionHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "WrapWithCondition"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            val matches = MixinExtrasSupport.findMatches(targetClass, targetMethod, annotation)
                .filter { MixinExtrasSupport.producedType(it).sort == Type.VOID }

            for (insn in matches) {
                val consumed = MixinExtrasSupport.consumedTypes(insn) ?: continue

                val list = InsnList()
                val slots = AsmHelper.stashStack(list, consumed, targetMethod)

                if (!isStatic) list.add(VarInsnNode(Opcodes.ALOAD, 0))
                AsmHelper.unstashStack(list, consumed, slots)
                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, consumed.size, targetClass, targetMethod)
                list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

                val skipLabel = LabelNode()
                list.add(JumpInsnNode(Opcodes.IFEQ, skipLabel))
                AsmHelper.unstashStack(list, consumed, slots)

                targetMethod.instructions.insertBefore(insn, list)
                targetMethod.instructions.insert(insn, skipLabel)
            }
        }
    }
}
