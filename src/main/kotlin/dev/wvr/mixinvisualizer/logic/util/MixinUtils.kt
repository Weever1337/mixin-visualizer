package dev.wvr.mixinvisualizer.logic.util

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

object AnnotationUtils {
    fun simpleName(desc: String): String = desc.substringAfterLast('/').removeSuffix(";")

    fun getValue(node: AnnotationNode, key: String): Any? {
        val values = node.values ?: return null
        for (i in 0 until values.size step 2) {
            if (values[i] == key) {
                return values[i + 1]
            }
        }
        return null
    }

    fun getListValue(node: AnnotationNode, key: String): List<String> {
        val values = node.values ?: return emptyList()
        for (i in 0 until values.size step 2) {
            if (values[i] == key) {
                val list = values[i + 1]
                if (list is List<*>) return list.map { it.toString() }
            }
        }
        return emptyList()
    }

    fun getAtValue(node: AnnotationNode, subKey: String): String {
        val values = node.values ?: return ""
        for (i in 0 until values.size step 2) {
            if (values[i] == "at") {
                val rawAt = values[i + 1]
                val atNode = if (rawAt is List<*>) {
                    rawAt.firstOrNull() as? AnnotationNode
                } else {
                    rawAt as? AnnotationNode
                } ?: continue

                val atArgs = atNode.values ?: continue
                for (j in 0 until atArgs.size step 2) {
                    if (atArgs[j] == subKey) {
                        val v = atArgs[j + 1]
                        if (v is List<*>) return v.firstOrNull()?.toString() ?: ""
                        if (v is Array<*>) return v.lastOrNull()?.toString() ?: ""
                        return v.toString()
                    }
                }
            }
        }
        return ""
    }
}

object TargetFinderUtils {
    fun parseMethodRef(rawRef: String): Triple<String?, String, String?>? {
        var ref = rawRef.trim()
        if (ref.isEmpty()) return null

        var desc: String? = null
        val parenIdx = ref.indexOf('(')
        if (parenIdx != -1) {
            desc = ref.substring(parenIdx)
            ref = ref.substring(0, parenIdx)
        }

        var owner: String? = null
        val semiIdx = ref.indexOf(';')
        if (semiIdx != -1) {
            var ownerPart = ref.substring(0, semiIdx)
            if (ownerPart.startsWith("L")) ownerPart = ownerPart.substring(1)
            owner = ownerPart.replace('.', '/')
            ref = ref.substring(semiIdx + 1)
        } else {
            val dotIdx = ref.lastIndexOf('.')
            if (dotIdx != -1) {
                owner = ref.substring(0, dotIdx).replace('.', '/')
                ref = ref.substring(dotIdx + 1)
            }
        }

        if (ref.isEmpty()) return null
        return Triple(owner, ref, desc)
    }

    fun findTargetMethodLike(classNode: ClassNode, ref: String): MethodNode? {
        val (_, name, desc) = parseMethodRef(ref) ?: return null
        return classNode.methods.find { it.name == name && (desc == null || it.desc == desc) }
    }

    fun isMatch(insn: MethodInsnNode, targetRef: String): Boolean {
        val (owner, name, desc) = parseMethodRef(targetRef) ?: return false
        if (insn.name != name) return false
        if (desc != null && insn.desc != desc) return false
        if (owner != null && insn.owner != owner) return false
        return true
    }

    fun isMatchField(insn: FieldInsnNode, targetRef: String): Boolean {
        var ref = targetRef

        val colonIndex = ref.lastIndexOf(':')
        var targetDesc: String? = null
        if (colonIndex != -1) {
            targetDesc = ref.substring(colonIndex + 1)
            ref = ref.take(colonIndex)
        }

        var targetOwner: String? = null
        val semiIndex = ref.lastIndexOf(';')
        if (semiIndex != -1) {
            var ownerPart = ref.take(semiIndex)
            if (ownerPart.startsWith("L")) ownerPart = ownerPart.substring(1)
            targetOwner = ownerPart
            ref = ref.substring(semiIndex + 1)
        } else {
            val dotIndex = ref.lastIndexOf('.')
            if (dotIndex != -1) {
                targetOwner = ref.take(dotIndex).replace('.', '/')
                ref = ref.substring(dotIndex + 1)
            }
        }

        val targetName = ref

        if (insn.name != targetName) return false
        if (targetDesc != null && insn.desc != targetDesc) return false
        if (targetOwner != null && insn.owner != targetOwner) return false

        return true
    }
}

data class GeneratedCode(
    val instructions: InsnList,
    val tryCatchBlocks: List<TryCatchBlockNode>
)

object LocalsSupport {
    fun paramAnnotation(method: MethodNode, paramIndex: Int, simpleName: String): AnnotationNode? {
        method.visibleParameterAnnotations?.getOrNull(paramIndex)
            ?.find { AnnotationUtils.simpleName(it.desc) == simpleName }?.let { return it }
        method.invisibleParameterAnnotations?.getOrNull(paramIndex)
            ?.find { AnnotationUtils.simpleName(it.desc) == simpleName }?.let { return it }
        return null
    }

    fun resolveLocalSlot(targetMethod: MethodNode, type: Type, ann: AnnotationNode): Int? {
        val index = AnnotationUtils.getValue(ann, "index") as? Int ?: -1
        if (index >= 0) return index

        val candidates = targetMethod.localVariables
            ?.filter { it.desc == type.descriptor }
            ?.sortedBy { it.index }
            ?: return null

        val ordinal = AnnotationUtils.getValue(ann, "ordinal") as? Int ?: -1
        return candidates.getOrNull(if (ordinal >= 0) ordinal else 0)?.index
    }

    fun shareFieldLoad(targetClass: ClassNode, ann: AnnotationNode, type: Type): FieldInsnNode {
        val id = AnnotationUtils.getValue(ann, "value") as? String ?: "shared"
        val name = "visor\$share\$" + id.replace(Regex("[^A-Za-z0-9_]"), "_")

        if (targetClass.fields.none { it.name == name }) {
            targetClass.fields.add(
                FieldNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, name, type.descriptor, null, null)
            )
        }
        return FieldInsnNode(Opcodes.GETSTATIC, targetClass.name, name, type.descriptor)
    }

    fun pushExtraArg(
        list: InsnList,
        source: MethodNode,
        paramIndex: Int,
        type: Type,
        targetClass: ClassNode,
        targetMethod: MethodNode
    ) {
        val localAnn = paramAnnotation(source, paramIndex, "Local")
        if (localAnn != null) {
            val slot = resolveLocalSlot(targetMethod, type, localAnn)
            if (slot != null) {
                list.add(VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot))
                return
            }
        }

        val shareAnn = paramAnnotation(source, paramIndex, "Share")
        if (shareAnn != null) {
            list.add(shareFieldLoad(targetClass, shareAnn, type))
            return
        }

        AsmHelper.pushDefaultValue(list, type)
    }
}

object CodeGenerationUtils {
    private const val CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo"
    private const val CALLBACK_INFO_RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"

    fun prepareCode(
        source: MethodNode,
        mixinName: String,
        targetClass: ClassNode,
        targetMethod: MethodNode,
        isRedirect: Boolean
    ): GeneratedCode {
        val labelMap = HashMap<LabelNode, LabelNode>()
        val code = AsmHelper.cloneInstructions(source.instructions, labelMap)
        val tryCatchBlocks = AsmHelper.cloneTryCatchBlocks(source, labelMap)

        val offset = targetMethod.maxLocals + 5

        val sourceArgs = Type.getArgumentTypes(source.desc)
        val targetArgs = Type.getArgumentTypes(targetMethod.desc)

        AsmHelper.cleanupReturnInstruction(code, !isRedirect)

        try {
            val ciIndex = findCallbackInfoVarIndex(source)
            processCallbackInfo(code, Type.getReturnType(targetMethod.desc), ciIndex)
        } catch (_: Exception) {
        }

        AsmHelper.remapMemberAccess(code, mixinName, targetClass.name)
        remapLocalVariables(code, source, targetMethod, offset, labelMap)

        if (!isRedirect && sourceArgs.size > targetArgs.size) {
            val stubInit = InsnList()
            var currentSlotIndex = AsmHelper.getArgsSize(targetMethod)
            val startIndex = targetArgs.size

            for (i in startIndex until sourceArgs.size) {
                val type = sourceArgs[i]
                if (type.internalName != CALLBACK_INFO && type.internalName != CALLBACK_INFO_RETURNABLE) {
                    LocalsSupport.pushExtraArg(stubInit, source, i, type, targetClass, targetMethod)
                    stubInit.add(VarInsnNode(type.getOpcode(Opcodes.ISTORE), currentSlotIndex + offset))
                }
                currentSlotIndex += type.size
            }
            code.insert(stubInit)
        }

        return GeneratedCode(code, tryCatchBlocks)
    }

    private fun findCallbackInfoVarIndex(method: MethodNode): Int {
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        var index = if (isStatic) 0 else 1
        for (arg in Type.getArgumentTypes(method.desc)) {
            if (arg.internalName == CALLBACK_INFO || arg.internalName == CALLBACK_INFO_RETURNABLE) {
                return index
            }
            index += arg.size
        }
        return -1
    }

    private fun processCallbackInfo(insns: InsnList, targetReturnType: Type, ciVarIndex: Int) {
        var node = insns.first
        while (node != null) {
            val next = node.next
            if (node is MethodInsnNode) {
                handleMethodCall(insns, node, targetReturnType, ciVarIndex)
            }
            node = next
        }
    }

    private fun handleMethodCall(insns: InsnList, insn: MethodInsnNode, targetReturnType: Type, ciVarIndex: Int) {
        if (insn.owner == CALLBACK_INFO && insn.name == "cancel" && insn.desc == "()V") {
            removeCiLoadAndCall(insns, insn, ciVarIndex)

            if (targetReturnType.sort != Type.VOID) {
                val nullInsn = when (targetReturnType.sort) {
                    Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> InsnNode(Opcodes.ICONST_0)
                    Type.FLOAT -> InsnNode(Opcodes.FCONST_0)
                    Type.LONG -> InsnNode(Opcodes.LCONST_0)
                    Type.DOUBLE -> InsnNode(Opcodes.DCONST_0)
                    else -> InsnNode(Opcodes.ACONST_NULL)
                }
                insns.insertBefore(insn, nullInsn)
            }

            insns.insertBefore(insn, InsnNode(targetReturnType.getOpcode(Opcodes.IRETURN)))
            insns.remove(insn)

        } else if (insn.owner == CALLBACK_INFO_RETURNABLE && insn.name == "setReturnValue") {
            val args = Type.getArgumentTypes(insn.desc)
            if (args.isNotEmpty()) {
                val valueType = args[0]

                val removedLoad = removeCiLoadIfPossible(insns, insn, ciVarIndex)

                if (!removedLoad) {
                    if (valueType.size == 1) {
                        insns.insertBefore(insn, InsnNode(Opcodes.SWAP))
                        insns.insertBefore(insn, InsnNode(Opcodes.POP))
                    } else {
                        insns.insertBefore(insn, InsnNode(Opcodes.DUP2_X1))
                        insns.insertBefore(insn, InsnNode(Opcodes.POP2))
                        insns.insertBefore(insn, InsnNode(Opcodes.POP))
                    }
                }

                adjustType(insns, insn, targetReturnType)
                insns.insertBefore(insn, InsnNode(targetReturnType.getOpcode(Opcodes.IRETURN)))
                insns.remove(insn)
            }
        }
    }

    private fun removeCiLoadAndCall(insns: InsnList, callInsn: AbstractInsnNode, ciIndex: Int) {
        val prev = callInsn.previous
        if (prev is VarInsnNode && prev.opcode == Opcodes.ALOAD && prev.`var` == ciIndex) {
            insns.remove(prev)
        } else {
            insns.insertBefore(callInsn, InsnNode(Opcodes.POP))
        }
    }

    private fun removeCiLoadIfPossible(insns: InsnList, callInsn: AbstractInsnNode, ciIndex: Int): Boolean {
        var current = callInsn.previous
        var steps = 0
        while (current != null && steps < 10) {
            if (current is VarInsnNode && current.opcode == Opcodes.ALOAD && current.`var` == ciIndex) {
                insns.remove(current)
                return true
            }
            steps++
            current = current.previous
        }
        return false
    }

    private fun adjustType(insns: InsnList, location: AbstractInsnNode, targetType: Type) {
        if (targetType.sort != Type.OBJECT && targetType.sort != Type.ARRAY && targetType.sort != Type.VOID) {
            val internalName = getWrapperInternalName(targetType)
            val methodName = getUnboxMethodName(targetType)
            val desc = "()" + targetType.descriptor

            insns.insertBefore(location, TypeInsnNode(Opcodes.CHECKCAST, internalName))
            insns.insertBefore(location, MethodInsnNode(Opcodes.INVOKEVIRTUAL, internalName, methodName, desc, false))
        } else if (targetType.sort == Type.OBJECT || targetType.sort == Type.ARRAY) {
            if (targetType.internalName != "java/lang/Object") {
                insns.insertBefore(location, TypeInsnNode(Opcodes.CHECKCAST, targetType.internalName))
            }
        }
    }

    private fun getWrapperInternalName(type: Type): String {
        return when (type.sort) {
            Type.BOOLEAN -> "java/lang/Boolean"
            Type.CHAR -> "java/lang/Character"
            Type.BYTE -> "java/lang/Byte"
            Type.SHORT -> "java/lang/Short"
            Type.INT -> "java/lang/Integer"
            Type.FLOAT -> "java/lang/Float"
            Type.LONG -> "java/lang/Long"
            Type.DOUBLE -> "java/lang/Double"
            else -> "java/lang/Object"
        }
    }

    private fun getUnboxMethodName(type: Type): String {
        return when (type.sort) {
            Type.BOOLEAN -> "booleanValue"
            Type.CHAR -> "charValue"
            Type.BYTE -> "byteValue"
            Type.SHORT -> "shortValue"
            Type.INT -> "intValue"
            Type.FLOAT -> "floatValue"
            Type.LONG -> "longValue"
            Type.DOUBLE -> "doubleValue"
            else -> "toString"
        }
    }

    private fun remapLocalVariables(
        insns: InsnList,
        source: MethodNode,
        target: MethodNode,
        offset: Int,
        labelMap: Map<LabelNode, LabelNode>
    ) {
        val targetArgSlotLimit = AsmHelper.getArgsSize(target)
        val sourceArgSlotLimit = AsmHelper.getArgsSize(source)
        val boundary = minOf(targetArgSlotLimit, sourceArgSlotLimit)

        val iter = insns.iterator()
        while (iter.hasNext()) {
            val insn = iter.next()
            if (insn is VarInsnNode) {
                if (insn.`var` >= boundary) {
                    insn.`var` += offset
                }
            } else if (insn is IincInsnNode) {
                if (insn.`var` >= boundary) {
                    insn.`var` += offset
                }
            }
        }

        if (source.localVariables != null) {
            if (target.localVariables == null) {
                target.localVariables = ArrayList()
            }
            for (lvn in source.localVariables) {
                if (lvn.index < boundary) continue

                val newStart = labelMap[lvn.start]
                val newEnd = labelMap[lvn.end]
                if (newStart != null && newEnd != null) {
                    target.localVariables.add(
                        LocalVariableNode(lvn.name, lvn.desc, lvn.signature, newStart, newEnd, lvn.index + offset)
                    )
                }
            }
        }
        target.maxLocals += (source.maxLocals + 20)
    }
}

object SliceHelper {
    fun getSliceRange(
        targetClass: ClassNode,
        targetMethod: MethodNode,
        annotationNode: AnnotationNode
    ): Pair<AbstractInsnNode?, AbstractInsnNode?> {
        val raw = AnnotationUtils.getValue(annotationNode, "slice")
        val sliceNode = when (raw) {
            is AnnotationNode -> raw
            is List<*> -> raw.firstOrNull() as? AnnotationNode
            else -> null
        } ?: return targetMethod.instructions.first to targetMethod.instructions.last

        val fromAnnotation = AnnotationUtils.getValue(sliceNode, "from") as? AnnotationNode
        val toAnnotation = AnnotationUtils.getValue(sliceNode, "to") as? AnnotationNode

        val startNode = if (fromAnnotation != null) findSelector(targetClass, targetMethod, fromAnnotation) else null
        val endNode = if (toAnnotation != null) findSelector(targetClass, targetMethod, toAnnotation) else null

        return startNode to endNode
    }

    fun filterBySlice(
        targetClass: ClassNode,
        targetMethod: MethodNode,
        annotationNode: AnnotationNode,
        candidates: List<AbstractInsnNode>
    ): List<AbstractInsnNode> {
        if (AnnotationUtils.getValue(annotationNode, "slice") == null) return candidates
        val (start, end) = getSliceRange(targetClass, targetMethod, annotationNode)

        val inRange = HashSet<AbstractInsnNode>()
        var started = (start == null)
        var p = targetMethod.instructions.first
        while (p != null) {
            if (p === start) started = true
            if (started) inRange.add(p)
            if (p === end) break
            p = p.next
        }
        return candidates.filter { it in inRange }
    }

    private fun findSelector(
        owner: ClassNode,
        method: MethodNode,
        at: AnnotationNode
    ): AbstractInsnNode? {
        val value = AnnotationUtils.getValue(at, "value")
        val target = AnnotationUtils.getValue(at, "target")
        val targetStr = when (target) {
            is String -> target
            is List<*> -> target.firstOrNull()?.toString() ?: ""
            else -> ""
        }

        if (value == "HEAD") return method.instructions.first
        if (value == "TAIL") {
            var insn = method.instructions.last
            while (insn != null && insn.opcode !in Opcodes.IRETURN..Opcodes.RETURN) insn = insn.previous
            return insn
        }

        val iter = method.instructions.iterator()
        while (iter.hasNext()) {
            val insn = iter.next()
            when (value) {
                "RETURN" -> if (insn.opcode in Opcodes.IRETURN..Opcodes.RETURN) return insn
                "INVOKE" -> if (insn is MethodInsnNode && targetStr.isNotEmpty() &&
                    TargetFinderUtils.isMatch(insn, targetStr)
                ) return insn
                "FIELD" -> if (insn is FieldInsnNode && targetStr.isNotEmpty() &&
                    TargetFinderUtils.isMatchField(insn, targetStr)
                ) return insn
            }
        }

        return null
    }
}