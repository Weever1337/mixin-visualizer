package dev.wvr.mixinvisualizer.logic

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.handlers.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

class MixinTransformer {
    private val handlers: List<MixinHandler> = listOf(
        ModifyReturnValueHandler(),
        InjectHandler(),
        OverwriteHandler(),
        RedirectHandler(),

        ModifyArgHandler(),
        ModifyConstantHandler(),
        ModifyVariableHandler(),

        AccessorHandler(),
        InvokerHandler()
    )

    fun transform(target: ClassNode, mixin: ClassNode) {
        if (target.fields.none { it.name == "__mixin_fields_here__" }) {
            target.fields.add(
                FieldNode(
                    Opcodes.ACC_PUBLIC,
                    "__mixin_fields_here__",
                    "Ljava/lang/String;",
                    null,
                    "Applied: ${mixin.name}"
                )
            )
        }

        mergeUniqueMembers(target, mixin)
        mergeClinit(target, mixin)

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
                val desc = ann.desc ?: ""
                desc.contains("Inject") || desc.contains("Redirect") || desc.contains("Overwrite")
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
                    target.methods.add(newMethod)
                }
            }
        }
    }

    private fun isShadow(annotations: List<AnnotationNode>?): Boolean {
        return annotations?.any { it.desc.contains("Shadow") } == true
    }
}