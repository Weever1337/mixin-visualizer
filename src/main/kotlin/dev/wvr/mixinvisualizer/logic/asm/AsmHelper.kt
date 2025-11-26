package dev.wvr.mixinvisualizer.logic.asm

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

object AsmHelper {
    fun cloneInstructions(list: InsnList, map: MutableMap<LabelNode, LabelNode>? = null): InsnList {
        val clone = InsnList()
        val labels = map ?: mutableMapOf()

        var p = list.first
        while (p != null) {
            if (p is LabelNode) {
                if (!labels.containsKey(p)) {
                    labels[p] = LabelNode()
                }
            }
            p = p.next
        }

        p = list.first
        while (p != null) {
            clone.add(p.clone(labels))
            p = p.next
        }
        return clone
    }

    fun cloneTryCatchBlocks(
        blocks: List<TryCatchBlockNode>,
        labelMap: Map<LabelNode, LabelNode>
    ): List<TryCatchBlockNode> {
        val newBlocks = ArrayList<TryCatchBlockNode>()
        for (tcb in blocks) {
            val start = labelMap[tcb.start]
            val end = labelMap[tcb.end]
            val handler = labelMap[tcb.handler]

            if (start != null && end != null && handler != null) {
                val newTcb = TryCatchBlockNode(start, end, handler, tcb.type)
                newTcb.visibleTypeAnnotations = tcb.visibleTypeAnnotations
                newTcb.invisibleTypeAnnotations = tcb.invisibleTypeAnnotations
                newBlocks.add(newTcb)
            }
        }
        return newBlocks
    }

    fun cloneTryCatchBlocks(
        sourceMethod: MethodNode,
        labelMap: Map<LabelNode, LabelNode>
    ): List<TryCatchBlockNode> {
        return cloneTryCatchBlocks(sourceMethod.tryCatchBlocks, labelMap)
    }

    fun remapMemberAccess(insns: InsnList, oldOwner: String, newOwner: String) {
        val iter = insns.iterator()
        while (iter.hasNext()) {
            val insn = iter.next()
            if (insn is FieldInsnNode && insn.owner == oldOwner) insn.owner = newOwner
            else if (insn is MethodInsnNode && insn.owner == oldOwner) insn.owner = newOwner
        }
    }

    fun getArgsSize(method: MethodNode): Int {
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        var size = if (isStatic) 0 else 1
        for (type in Type.getArgumentTypes(method.desc)) size += type.size
        return size
    }

    fun cleanupReturnInstruction(list: InsnList, allowValueReturns: Boolean) {
        val endLabel = LabelNode()
        list.add(endLabel)
        var last = list.first
        while (last != null) {
            val next = last.next // FIXME(?): save link to next modifier (is it necessary?)

            if (last.opcode == Opcodes.RETURN || (!allowValueReturns && last.opcode in Opcodes.IRETURN..Opcodes.RETURN)) {
                list.set(last, JumpInsnNode(Opcodes.GOTO, endLabel))
            }
            last = next
        }
    }

    fun generateDefaultValue(list: InsnList, type: Type, varIndex: Int) {
        val opStore = type.getOpcode(Opcodes.ISTORE)
        when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> list.add(InsnNode(Opcodes.ICONST_0))
            Type.FLOAT -> list.add(InsnNode(Opcodes.FCONST_0))
            Type.LONG -> list.add(InsnNode(Opcodes.LCONST_0))
            Type.DOUBLE -> list.add(InsnNode(Opcodes.DCONST_0))
            else -> list.add(InsnNode(Opcodes.ACONST_NULL))
        }
        list.add(VarInsnNode(opStore, varIndex))
    }
}