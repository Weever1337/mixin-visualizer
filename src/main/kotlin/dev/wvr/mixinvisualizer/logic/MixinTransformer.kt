package dev.wvr.mixinvisualizer.logic

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.handlers.*
import dev.wvr.mixinvisualizer.logic.util.AnnotationUtils
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

class MixinTransformer {
    private val handlers: List<MixinHandler> = listOf(
        InjectHandler(),
        OverwriteHandler(),
        RedirectHandler(),

        ModifyArgHandler(),
        ModifyConstantHandler(),
        ModifyVariableHandler(),

        AccessorHandler(),
        InvokerHandler(),

        ModifyReturnValueHandler(),
        ModifyExpressionValueHandler(),
        ModifyReceiverHandler(),
        WrapOperationHandler(),
        WrapWithConditionHandler(),
        WrapMethodHandler()
    )

    fun transform(target: ClassNode, mixin: ClassNode) {
        mergeUniqueMembers(target, mixin)
        mergeClinit(target, mixin)
        try {
            mergeFieldInitializers(target, mixin)
        } catch (e: Exception) {
            System.err.println("Failed to merge mixin field initializers from ${mixin.name}: ${e.message}")
        }

        for (mixinMethod in mixin.methods) {
            val anns = mixinMethod.visibleAnnotations ?: continue
            for (ann in anns) {
                val desc = ann.desc ?: ""
                val handler = handlers.find { it.canHandle(desc) }

                try {
                    handler?.handle(target, mixin, mixinMethod, ann)
                } catch (e: Exception) {
                    System.err.println("Failed to apply mixin handler ${handler?.javaClass?.simpleName} for ${mixinMethod.name}: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun mergeClinit(target: ClassNode, mixin: ClassNode) {
        val mixinClinit = mixin.methods.find { it.name == "<clinit>" } ?: return
        var targetClinit = target.methods.find { it.name == "<clinit>" }

        if (targetClinit == null) {
            targetClinit = MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
            target.methods.add(targetClinit)
            targetClinit.instructions.add(InsnNode(Opcodes.RETURN))
        }

        val code = AsmHelper.cloneInstructions(mixinClinit.instructions)
        AsmHelper.remapMemberAccess(code, mixin.name, target.name)

        var node = code.last
        while (node != null) {
            val prev = node.previous
            if (node.opcode == Opcodes.RETURN) {
                code.remove(node)
            } else if (node.opcode != -1) {
                break
            }
            node = prev
        }

        val offset = targetClinit.maxLocals
        val iter = code.iterator()
        while (iter.hasNext()) {
            val insn = iter.next()
            if (insn is VarInsnNode) {
                insn.`var` += offset
            } else if (insn is IincInsnNode) {
                insn.`var` += offset
            }
        }
        targetClinit.maxLocals += mixinClinit.maxLocals

        val targetLast = targetClinit.instructions.last
        if (targetLast != null && targetLast.opcode == Opcodes.RETURN) {
            targetClinit.instructions.insertBefore(targetLast, code)
        } else {
            targetClinit.instructions.add(code)
            targetClinit.instructions.add(InsnNode(Opcodes.RETURN))
        }
    }

    private fun mergeFieldInitializers(target: ClassNode, mixin: ClassNode) {
        val mixinCtor = mixin.methods.find { it.name == "<init>" && it.desc == "()V" } ?: return
        val superCall = AsmHelper.findSuperCall(mixinCtor, mixin.superName) ?: return

        val labels = HashMap<LabelNode, LabelNode>()
        var p: AbstractInsnNode? = superCall.next
        while (p != null) {
            if (p is LabelNode) labels[p] = LabelNode()
            p = p.next
        }
        val tail = InsnList()
        var insn: AbstractInsnNode? = superCall.next
        while (insn != null) {
            tail.add(insn.clone(labels))
            insn = insn.next
        }

        var last = tail.last
        while (last != null) {
            val prev = last.previous
            if (last.opcode == Opcodes.RETURN) tail.remove(last)
            else if (last.opcode != -1) break
            last = prev
        }

        if (tail.toArray().none { it.opcode != -1 }) return

        AsmHelper.remapMemberAccess(tail, mixin.name, target.name)

        for (ctor in target.methods) {
            if (ctor.name != "<init>") continue
            val targetSuper = AsmHelper.findSuperCall(ctor, target.superName) ?: continue

            val map = HashMap<LabelNode, LabelNode>()
            val code = AsmHelper.cloneInstructions(tail, map)

            val offset = ctor.maxLocals
            val iter = code.iterator()
            while (iter.hasNext()) {
                val i = iter.next()
                if (i is VarInsnNode && i.`var` >= 1) i.`var` += offset
                else if (i is IincInsnNode && i.`var` >= 1) i.`var` += offset
            }
            ctor.maxLocals += mixinCtor.maxLocals

            ctor.instructions.insert(targetSuper, code)
        }
    }

    private fun mergeUniqueMembers(target: ClassNode, mixin: ClassNode) {
        for (field in mixin.fields) {
            if (isShadow(field.visibleAnnotations)) continue

            if (target.fields.none { it.name == field.name && it.desc == field.desc }) {
                val newAccess = (field.access and Opcodes.ACC_PRIVATE.inv()) or Opcodes.ACC_PUBLIC
                target.fields.add(FieldNode(newAccess, field.name, field.desc, field.signature, field.value))
            }
        }

        for (method in mixin.methods) {
            if (isShadow(method.visibleAnnotations)) continue

            val anns = method.visibleAnnotations ?: emptyList()

            val isInjector = anns.any { ann ->
                AnnotationUtils.simpleName(ann.desc ?: "") in INLINED_INJECTORS
            }

            if (!isInjector && method.name != "<init>" && method.name != "<clinit>") {
                if (target.methods.none { it.name == method.name && it.desc == method.desc }) {
                    val newMethod = MethodNode(
                        (method.access and Opcodes.ACC_PRIVATE.inv()) or Opcodes.ACC_PUBLIC,
                        method.name,
                        method.desc,
                        method.signature,
                        method.exceptions?.toTypedArray()
                    )
                    method.accept(newMethod)
                    AsmHelper.remapMemberAccess(newMethod.instructions, mixin.name, target.name)
                    AsmHelper.stripMixinAnnotations(newMethod)
                    target.methods.add(newMethod)
                }
            }
        }
    }

    private fun isShadow(annotations: List<AnnotationNode>?): Boolean {
        return annotations?.any { it.desc.contains("Shadow") } == true
    }

    companion object {
        private val INLINED_INJECTORS = setOf("Inject", "Redirect", "Overwrite")
    }
}