package dev.wvr.mixinvisualizer.logic

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import dev.wvr.mixinvisualizer.util.BytecodeUtils
import java.io.File

class MixinProcessor(private val project: Project) {
    private val transformer = MixinTransformer()

    fun process(mixinPsiFile: PsiFile, showBytecode: Boolean): Pair<String, String> {
        if (mixinPsiFile !is PsiJavaFile) return "Err" to "Not a java file"

        return ReadAction.compute<Pair<String, String>, RuntimeException> {
            try {
                val clazz = mixinPsiFile.classes.firstOrNull() ?: return@compute "" to "// Class not found"
                val targetRef = findTargetClass(clazz) ?: return@compute "" to "// No @Mixin annotation"

                val targetPsi = JavaPsiFacade.getInstance(project)
                    .findClass(targetRef, GlobalSearchScope.allScope(project))
                    ?: return@compute "" to "// Target $targetRef not found"

                val targetBytes = findBytecode(targetPsi)
                    ?: return@compute "" to "// Original bytecode not found"

                val originalContent = if (showBytecode) BytecodeUtils.toAsmTrace(targetBytes)
                else BytecodeUtils.decompile(targetRef, targetBytes)

                val targetNode = BytecodeUtils.readClassNode(targetBytes)

                val mixinBytes = findCompiledBytecodeForSource(mixinPsiFile)
                    ?: return@compute originalContent to "// PLEASE COMPILE THE PROJECT FIRST (Ctrl+F9)"
                val mixinNode = BytecodeUtils.readClassNode(mixinBytes)

                transformer.transform(targetNode, mixinNode)

                val transformedBytes = BytecodeUtils.writeClassNode(targetNode)

                val transformedContent = if (showBytecode) BytecodeUtils.toAsmTrace(transformedBytes)
                else BytecodeUtils.decompile(targetRef, transformedBytes)

                return@compute originalContent to transformedContent

            } catch (e: Throwable) {
                return@compute "" to "// Error: ${e.message}\n${e.stackTraceToString()}"
            }
        }
    }

    private fun findTargetClass(mixinClass: PsiClass): String? {
        val ann = mixinClass.getAnnotation("org.spongepowered.asm.mixin.Mixin") ?: return null
        val value = ann.findAttributeValue("value")

        if (value is PsiClassObjectAccessExpression) return value.operand.type.canonicalText
        if (value is PsiLiteralExpression) return value.value as? String
        if (value is PsiArrayInitializerMemberValue) {
            val first = value.initializers.firstOrNull()
            if (first is PsiClassObjectAccessExpression) return first.operand.type.canonicalText
        }
        return null
    }

    private fun findBytecode(psiClass: PsiClass): ByteArray? {
        val vFile = psiClass.containingFile?.virtualFile ?: return null
        if (vFile.fileType.isBinary) return vFile.contentsToByteArray()
        return findCompiledBytecodeForSource(psiClass.containingFile)
    }

    private fun findCompiledBytecodeForSource(psiFile: PsiFile): ByteArray? {
        val vFile = psiFile.virtualFile ?: return null
        val module = ProjectRootManager.getInstance(project).fileIndex.getModuleForFile(vFile) ?: return null
        val compilerOutput = com.intellij.openapi.roots.CompilerModuleExtension.getInstance(module)?.compilerOutputPath

        if (compilerOutput != null) {
            val pkg = (psiFile as? PsiJavaFile)?.packageName ?: ""
            val relPath = pkg.replace('.', '/') + "/" + vFile.nameWithoutExtension + ".class"
            val file = File(compilerOutput.path, relPath)
            if (file.exists()) return file.readBytes()
        }
        return null
    }
}