package dev.wvr.mixinvisualizer.logic.handlers

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import dev.wvr.mixinvisualizer.logic.util.SliceHelper
import dev.wvr.mixinvisualizer.logic.util.TargetFinderUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

class ModifyConstantHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyConstant"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val handlerName = copyMethodToTarget(targetClass, mixinClass, sourceMethod)
        val valueType = Type.getReturnType(sourceMethod.desc)
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        val selectors = when (val raw = AnnotationUtils.getValue(annotation, "constant")) {
            is AnnotationNode -> listOf(raw)
            is List<*> -> raw.filterIsInstance<AnnotationNode>()
            else -> emptyList()
        }

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            val constants = SliceHelper.filterBySlice(
                targetClass, targetMethod, annotation,
                targetMethod.instructions.toArray().filter { constantValue(it) != null }
            )

            val matches = if (selectors.isEmpty()) {
                constants.filter { isTypeMatch(constantValue(it)!!, valueType) }
            } else {
                selectors.flatMap { selector ->
                    val found = constants.filter { matchesSelector(constantValue(it)!!, selector, valueType) }
                    val ordinal = AnnotationUtils.getValue(selector, "ordinal") as? Int ?: -1
                    if (ordinal >= 0) listOfNotNull(found.getOrNull(ordinal)) else found
                }.distinct()
            }

            for (insn in matches) {
                if (!isStatic) targetMethod.instructions.insertBefore(insn, VarInsnNode(Opcodes.ALOAD, 0))

                val list = InsnList()
                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, 1, targetClass, targetMethod)
                list.add(
                    MethodInsnNode(
                        if (isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                        targetClass.name,
                        handlerName,
                        sourceMethod.desc,
                        false
                    )
                )
                targetMethod.instructions.insert(insn, list)
            }
        }
    }

    private fun constantValue(insn: AbstractInsnNode): Any? = when (insn.opcode) {
        Opcodes.ACONST_NULL -> NULL_CONSTANT
        in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> insn.opcode - Opcodes.ICONST_0
        Opcodes.LCONST_0, Opcodes.LCONST_1 -> (insn.opcode - Opcodes.LCONST_0).toLong()
        Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 -> (insn.opcode - Opcodes.FCONST_0).toFloat()
        Opcodes.DCONST_0, Opcodes.DCONST_1 -> (insn.opcode - Opcodes.DCONST_0).toDouble()
        Opcodes.BIPUSH, Opcodes.SIPUSH -> (insn as IntInsnNode).operand
        Opcodes.LDC -> (insn as LdcInsnNode).cst
        else -> null
    }

    private fun matchesSelector(value: Any, selector: AnnotationNode, type: Type): Boolean {
        if (AnnotationUtils.getValue(selector, "nullValue") == true) return value === NULL_CONSTANT
        for (key in VALUE_KEYS) {
            val expected = AnnotationUtils.getValue(selector, key) ?: continue
            return value == expected
        }
        return isTypeMatch(value, type)
    }

    private fun isTypeMatch(value: Any, type: Type): Boolean = when (type.sort) {
        Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.BOOLEAN -> value is Int
        Type.FLOAT -> value is Float
        Type.LONG -> value is Long
        Type.DOUBLE -> value is Double
        Type.OBJECT -> (value is String && type.internalName == "java/lang/String") ||
                (value is Type && type.internalName == "java/lang/Class")
        else -> false
    }

    companion object {
        private val NULL_CONSTANT = Any()
        private val VALUE_KEYS = listOf("intValue", "floatValue", "longValue", "doubleValue", "stringValue", "classValue")
    }
}

class ModifyVariableHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyVariable"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val atValue = AnnotationUtils.getAtValue(annotation, "value")
        val atOrdinal = AnnotationUtils.getAtValue(annotation, "ordinal").toIntOrNull() ?: -1
        val varType = Type.getReturnType(sourceMethod.desc)
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0

        val handlerName = copyMethodToTarget(targetClass, mixinClass, sourceMethod)

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue
            val slot = findSlot(targetMethod, annotation, varType) ?: continue
            val insns = targetMethod.instructions.toArray()

            var points: List<AbstractInsnNode> = when (atValue) {
                "HEAD" -> listOfNotNull(AsmHelper.headInsn(targetClass, targetMethod))
                "RETURN" -> insns.filter { it.opcode in Opcodes.IRETURN..Opcodes.RETURN }
                "TAIL" -> listOfNotNull(insns.lastOrNull { it.opcode in Opcodes.IRETURN..Opcodes.RETURN })
                "STORE" -> insns.filter { it is VarInsnNode && it.`var` == slot && it.opcode == varType.getOpcode(Opcodes.ISTORE) }
                "LOAD" -> insns.filter { it is VarInsnNode && it.`var` == slot && it.opcode == varType.getOpcode(Opcodes.ILOAD) }
                "INVOKE", "FIELD" -> MixinExtrasSupport.findMatches(targetClass, targetMethod, annotation)
                else -> emptyList()
            }
            if (atValue != "INVOKE" && atValue != "FIELD") {
                points = SliceHelper.filterBySlice(targetClass, targetMethod, annotation, points)
                if (atOrdinal >= 0) points = listOfNotNull(points.getOrNull(atOrdinal))
            }

            for (point in points) {
                val list = InsnList()
                if (!isStatic) list.add(VarInsnNode(Opcodes.ALOAD, 0))
                list.add(VarInsnNode(varType.getOpcode(Opcodes.ILOAD), slot))
                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, 1, targetClass, targetMethod)
                list.add(
                    MethodInsnNode(
                        if (isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKEVIRTUAL,
                        targetClass.name,
                        handlerName,
                        sourceMethod.desc,
                        false
                    )
                )
                list.add(VarInsnNode(varType.getOpcode(Opcodes.ISTORE), slot))

                if (atValue == "STORE") targetMethod.instructions.insert(point, list)
                else targetMethod.instructions.insertBefore(point, list)
            }
        }
    }

    private class Variable(val slot: Int, val desc: String, val name: String?)

    private fun findSlot(method: MethodNode, annotation: AnnotationNode, type: Type): Int? {
        val index = AnnotationUtils.getValue(annotation, "index") as? Int ?: -1
        if (index >= 0) return index

        val argsOnly = AnnotationUtils.getValue(annotation, "argsOnly") == true
        val candidates = variables(method, argsOnly).filter { it.desc == type.descriptor }

        val names = AnnotationUtils.getListValue(annotation, "name")
        if (names.isNotEmpty()) return candidates.firstOrNull { it.name in names }?.slot

        val ordinal = AnnotationUtils.getValue(annotation, "ordinal") as? Int ?: -1
        if (ordinal >= 0) return candidates.getOrNull(ordinal)?.slot

        return candidates.singleOrNull()?.slot
    }

    private fun variables(method: MethodNode, argsOnly: Boolean): List<Variable> {
        val lvt = method.localVariables ?: emptyList()
        val result = mutableListOf<Variable>()

        var slot = if ((method.access and Opcodes.ACC_STATIC) != 0) 0 else 1
        for (arg in Type.getArgumentTypes(method.desc)) {
            result.add(Variable(slot, arg.descriptor, lvt.firstOrNull { it.index == slot }?.name))
            slot += arg.size
        }
        if (argsOnly) return result

        lvt.filter { it.index >= slot }
            .sortedBy { it.index }
            .distinctBy { it.index to it.desc }
            .forEach { result.add(Variable(it.index, it.desc, it.name)) }
        return result
    }
}

class ModifyArgHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyArg"

    override fun handle(
        targetClass: ClassNode,
        mixinClass: ClassNode,
        sourceMethod: MethodNode,
        annotation: AnnotationNode
    ) {
        val targets = AnnotationUtils.getListValue(annotation, "method")
        val explicitIndex = AnnotationUtils.getValue(annotation, "index") as? Int ?: -1
        val isStatic = (sourceMethod.access and Opcodes.ACC_STATIC) != 0
        val handlerValueType = Type.getReturnType(sourceMethod.desc)

        for (ref in targets) {
            val targetMethod = TargetFinderUtils.findTargetMethodLike(targetClass, ref) ?: continue

            val matches = MixinExtrasSupport.findMatches(targetClass, targetMethod, annotation)
                .filterIsInstance<MethodInsnNode>()

            for (insn in matches) {
                val argTypes = Type.getArgumentTypes(insn.desc).toList()

                val index = if (explicitIndex >= 0) explicitIndex
                else argTypes.indexOfFirst { it == handlerValueType }
                if (index !in argTypes.indices) continue

                val list = InsnList()
                if (takesAllArgs(sourceMethod, argTypes)) {
                    val argSlots = AsmHelper.stashStack(list, argTypes, targetMethod)
                    if (!isStatic) list.add(VarInsnNode(Opcodes.ALOAD, 0))
                    AsmHelper.unstashStack(list, argTypes, argSlots)
                    MixinExtrasSupport.pushExtraArgs(list, sourceMethod, argTypes.size, targetClass, targetMethod)
                    list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

                    val valueSlot = AsmHelper.stashStack(list, listOf(argTypes[index]), targetMethod)
                    AsmHelper.unstashStack(list, argTypes.take(index), argSlots.copyOfRange(0, index))
                    AsmHelper.unstashStack(list, listOf(argTypes[index]), valueSlot)
                    AsmHelper.unstashStack(list, argTypes.drop(index + 1), argSlots.copyOfRange(index + 1, argTypes.size))
                } else {
                    val tailTypes = argTypes.drop(index + 1)
                    val valueType = argTypes[index]
                    val tailSlots = AsmHelper.stashStack(list, tailTypes, targetMethod)

                    if (!isStatic) {
                        val valueSlots = AsmHelper.stashStack(list, listOf(valueType), targetMethod)
                        list.add(VarInsnNode(Opcodes.ALOAD, 0))
                        AsmHelper.unstashStack(list, listOf(valueType), valueSlots)
                    }
                    MixinExtrasSupport.pushExtraArgs(list, sourceMethod, 1, targetClass, targetMethod)
                    list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))

                    AsmHelper.unstashStack(list, tailTypes, tailSlots)
                }

                targetMethod.instructions.insertBefore(insn, list)
            }
        }
    }

    private fun takesAllArgs(handler: MethodNode, argTypes: List<Type>): Boolean {
        val handlerArgs = Type.getArgumentTypes(handler.desc)
        return argTypes.size > 1 && handlerArgs.size >= argTypes.size && argTypes.indices.all { handlerArgs[it] == argTypes[it] }
    }
}

class ModifyArgsHandler : MixinHandler {
    override fun canHandle(annotationDesc: String): Boolean =
        AnnotationUtils.simpleName(annotationDesc) == "ModifyArgs"

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
                if (insn !is MethodInsnNode) continue
                val argTypes = Type.getArgumentTypes(insn.desc).toList()

                val list = InsnList()
                val argSlots = AsmHelper.stashStack(list, argTypes, targetMethod)
                if (!isStatic) list.add(VarInsnNode(Opcodes.ALOAD, 0))
                list.add(InsnNode(Opcodes.ACONST_NULL)) // TODO (modifyargs) real Args object instead of null
                MixinExtrasSupport.pushExtraArgs(list, sourceMethod, 1, targetClass, targetMethod)
                list.add(MixinExtrasSupport.invokeHandler(targetClass, sourceMethod))
                AsmHelper.unstashStack(list, argTypes, argSlots)

                targetMethod.instructions.insertBefore(insn, list)
            }
        }
    }
}

private fun copyMethodToTarget(targetClass: ClassNode, mixinClass: ClassNode, sourceMethod: MethodNode): String {
    val existing = targetClass.methods.find { it.name == sourceMethod.name && it.desc == sourceMethod.desc }
    if (existing != null) return existing.name

    val newName = sourceMethod.name + "\$visualized" + (System.nanoTime() % 10000)
    val newMethod = MethodNode(
        (sourceMethod.access and Opcodes.ACC_PRIVATE.inv()) or Opcodes.ACC_PUBLIC,
        newName,
        sourceMethod.desc,
        sourceMethod.signature,
        sourceMethod.exceptions?.toTypedArray()
    )
    sourceMethod.accept(newMethod)
    AsmHelper.remapMemberAccess(newMethod.instructions, mixinClass.name, targetClass.name)
    AsmHelper.stripMixinAnnotations(newMethod)
    targetClass.methods.add(newMethod)
    return newName
}