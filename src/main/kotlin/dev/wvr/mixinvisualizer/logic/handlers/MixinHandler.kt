package dev.wvr.mixinvisualizer.logic.handlers

import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

interface MixinHandler {
    fun canHandle(annotationDesc: String): Boolean
    fun handle(targetClass: ClassNode, mixinClass: ClassNode, sourceMethod: MethodNode, annotation: AnnotationNode)
}