package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.InjectionPoints
import dev.wvr.mixinvisualizer.logic.util.LocalsSupport
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

internal object MixinExtrasSupport {
    private const val OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation"
    private const val CALL_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;"
    private val METAFACTORY = Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/LambdaMetafactory",
        "metafactory",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
        false
    )

    fun operationLambda(targetClass: ClassNode, original: AbstractInsnNode, argTypes: List<Type>): InsnList {
        val body = InsnList()
        for (i in argTypes.indices) {
            body.add(VarInsnNode(Opcodes.ALOAD, 0))
            body.add(AsmHelper.pushInt(i))
            body.add(InsnNode(Opcodes.AALOAD))
            AsmHelper.unbox(body, argTypes[i])
        }
        body.add(original.clone(emptyMap()))
        AsmHelper.box(body, producedType(original))

        val name = addLambdaBody(targetClass, "lambda\$wrapOperation\$", true, body)
        return InsnList().also { it.add(lambdaFactory(targetClass, name, true)) }
    }

    fun originalLambda(targetClass: ClassNode, method: MethodNode, originalName: String): InsnList {
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        val isInterface = (targetClass.access and Opcodes.ACC_INTERFACE) != 0
        val argsSlot = if (isStatic) 0 else 1

        val body = InsnList()
        if (!isStatic) body.add(VarInsnNode(Opcodes.ALOAD, 0))
        Type.getArgumentTypes(method.desc).forEachIndexed { i, type ->
            body.add(VarInsnNode(Opcodes.ALOAD, argsSlot))
            body.add(AsmHelper.pushInt(i))
            body.add(InsnNode(Opcodes.AALOAD))
            AsmHelper.unbox(body, type)
        }
        body.add(
            MethodInsnNode(
                if (isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKESPECIAL,
                targetClass.name,
                originalName,
                method.desc,
                isInterface
            )
        )
        AsmHelper.box(body, Type.getReturnType(method.desc))

        val name = addLambdaBody(targetClass, "lambda\$wrapMethod\$", isStatic, body)
        val list = InsnList()
        if (!isStatic) list.add(VarInsnNode(Opcodes.ALOAD, 0))
        list.add(lambdaFactory(targetClass, name, isStatic))
        return list
    }

    private fun addLambdaBody(targetClass: ClassNode, prefix: String, isStatic: Boolean, body: InsnList): String {
        val name = AsmHelper.freeMethodName(targetClass, prefix)
        val access = Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC or if (isStatic) Opcodes.ACC_STATIC else 0
        val method = MethodNode(access, name, CALL_DESC, null, null)

        val start = LabelNode()
        val end = LabelNode()
        method.instructions.add(start)
        method.instructions.add(body)
        method.instructions.add(InsnNode(Opcodes.ARETURN))
        method.instructions.add(end)

        method.localVariables = ArrayList()
        if (!isStatic) method.localVariables.add(LocalVariableNode("this", "L${targetClass.name};", null, start, end, 0))
        method.localVariables.add(LocalVariableNode("args", "[Ljava/lang/Object;", null, start, end, if (isStatic) 0 else 1))

        targetClass.methods.add(method)
        return name
    }

    private fun lambdaFactory(targetClass: ClassNode, implName: String, implStatic: Boolean): InvokeDynamicInsnNode {
        val isInterface = (targetClass.access and Opcodes.ACC_INTERFACE) != 0
        val captured = if (implStatic) "" else "L${targetClass.name};"
        val impl = Handle(
            if (implStatic) Opcodes.H_INVOKESTATIC else Opcodes.H_INVOKESPECIAL,
            targetClass.name,
            implName,
            CALL_DESC,
            isInterface
        )
        val callType = Type.getType(CALL_DESC)
        return InvokeDynamicInsnNode("call", "($captured)L$OPERATION;", METAFACTORY, callType, impl, callType)
    }

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
        var captureIndex = 0
        for (i in consumedArgs until args.size) {
            if (LocalsSupport.isSugar(source, i)) {
                LocalsSupport.pushExtraArg(list, source, i, args[i], targetClass, targetMethod)
            } else {
                AsmHelper.pushArgOrDefault(list, targetMethod, captureIndex++, args[i])
            }
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
        else -> when (val value = AsmHelper.constantValue(insn)) {
            null -> Type.VOID_TYPE
            AsmHelper.NULL_CONSTANT -> Type.getType(Any::class.java)
            is Int -> Type.INT_TYPE
            is Long -> Type.LONG_TYPE
            is Float -> Type.FLOAT_TYPE
            is Double -> Type.DOUBLE_TYPE
            is String -> Type.getType(String::class.java)
            is Type -> Type.getType(Class::class.java)
            else -> Type.VOID_TYPE
        }
    }

    fun findMatches(
        targetClass: ClassNode,
        targetMethod: MethodNode,
        annotation: AnnotationNode
    ): List<AbstractInsnNode> = InjectionPoints.find(targetClass, targetMethod, annotation)
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

                val takesNothing = AsmHelper.constantValue(insn) != null || insn.opcode == Opcodes.GETSTATIC

                val list = InsnList()
                if (!isStatic) {
                    if (takesNothing) {
                        targetMethod.instructions.insertBefore(insn, VarInsnNode(Opcodes.ALOAD, 0))
                    } else {
                        val slots = AsmHelper.stashStack(list, listOf(valueType), targetMethod)
                        list.add(VarInsnNode(Opcodes.ALOAD, 0))
                        AsmHelper.unstashStack(list, listOf(valueType), slots)
                    }
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

                list.add(MixinExtrasSupport.operationLambda(targetClass, insn, consumed))
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

            val argsSize = AsmHelper.getArgsSize(targetMethod)
            val params = targetMethod.localVariables.orEmpty().filter { it.index < argsSize }.distinctBy { it.index }

            targetMethod.instructions.clear()
            targetMethod.tryCatchBlocks?.clear()

            val targetStatic = (targetMethod.access and Opcodes.ACC_STATIC) != 0
            val argTypes = Type.getArgumentTypes(targetMethod.desc)

            val start = LabelNode()
            val end = LabelNode()
            targetMethod.localVariables = params.mapTo(ArrayList()) {
                LocalVariableNode(it.name, it.desc, it.signature, start, end, it.index)
            }

            val insns = InsnList()
            insns.add(start)
            if (!handlerStatic) insns.add(VarInsnNode(Opcodes.ALOAD, 0))

            var slot = if (targetStatic) 0 else 1
            for (arg in argTypes) {
                insns.add(VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot))
                slot += arg.size
            }

            insns.add(MixinExtrasSupport.originalLambda(targetClass, targetMethod, origName))
            MixinExtrasSupport.pushExtraArgs(insns, sourceMethod, argTypes.size + 1, targetClass, targetMethod)
            insns.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

            val returnType = Type.getReturnType(targetMethod.desc)
            insns.add(InsnNode(returnType.getOpcode(Opcodes.IRETURN)))
            insns.add(end)

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
