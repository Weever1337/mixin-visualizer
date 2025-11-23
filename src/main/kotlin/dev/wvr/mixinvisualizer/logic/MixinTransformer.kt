package dev.wvr.mixinvisualizer.logic

import dev.wvr.mixinvisualizer.logic.asm.AsmHelper
import dev.wvr.mixinvisualizer.logic.handlers.InjectHandler
import dev.wvr.mixinvisualizer.logic.handlers.MixinHandler
import dev.wvr.mixinvisualizer.logic.handlers.OverwriteHandler
import dev.wvr.mixinvisualizer.logic.handlers.RedirectHandler
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

class MixinTransformer {
    private val handlers: List<MixinHandler> = listOf(
        InjectHandler(),
        OverwriteHandler(),
        RedirectHandler()
    )

    fun transform(target: ClassNode, mixin: ClassNode) {
        if (target.fields.none { it.name == "__mixin_fields_here__" }) {
            target.fields.add(FieldNode(Opcodes.ACC_PUBLIC, "__mixin_fields_here__", "Ljava/lang/String;", null, "Applied: ${mixin.name}"))
        }

        mergeUniqueMembers(target, mixin)

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

    private fun mergeUniqueMembers(target: ClassNode, mixin: ClassNode) {
        for (field in mixin.fields) {
            if (target.fields.none { it.name == field.name && it.desc == field.desc }) {
                val newAccess = (field.access and Opcodes.ACC_PRIVATE.inv()) or Opcodes.ACC_PUBLIC
                target.fields.add(FieldNode(newAccess, field.name, field.desc, field.signature, field.value))
            }
        }

        for (method in mixin.methods) {
            val anns = method.visibleAnnotations ?: emptyList()
            val isHandler = anns.any { ann -> handlers.any { it.canHandle(ann.desc) } }

            if (!isHandler && method.name != "<init>" && method.name != "<clinit>") {
                if (target.methods.none { it.name == method.name && it.desc == method.desc }) {
                    val newMethod = MethodNode((method.access and Opcodes.ACC_PRIVATE.inv()) or Opcodes.ACC_PUBLIC, method.name, method.desc, method.signature, method.exceptions?.toTypedArray())
                    method.accept(newMethod)
                    AsmHelper.remapMemberAccess(newMethod.instructions, mixin.name, target.name)
                    target.methods.add(newMethod)
                }
            }
        }
    }
}