package dev.wvr.mixinvisualizer.ui

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import com.intellij.util.Function

class MixinLineMarkerProvider : LineMarkerProvider {
    companion object {
        const val MIXIN_EDITOR_ID = "mixin-vis-editor"
        private const val MIXIN_PACKAGE = "org.spongepowered.asm.mixin"
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null

        val parent = element.parent
        if (parent !is PsiClass && parent !is PsiMethod) return null

        val virtualFile = element.containingFile.virtualFile
        if (virtualFile == null || !ProjectRootManager.getInstance(element.project).fileIndex.isInSourceContent(virtualFile)) {
            return null
        }

        val isTarget = when (parent) {
            is PsiClass -> isMixinClass(parent)
            is PsiMethod -> hasMixinAnnotation(parent)
            else -> false
        }

        if (!isTarget) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Preview,
            tooltipProvider,
            navigationHandler,
            GutterIconRenderer.Alignment.RIGHT
        ) { "Mixin Visualizer" }
    }

    private val tooltipProvider = Function<PsiElement, String> { element ->
        if (element.parent is PsiClass) "Preview Mixin Result"
        else "Show in Mixin Visualizer"
    }

    private val navigationHandler = GutterIconNavigationHandler<PsiElement> { _, elt ->
        val project = elt.project
        val file = elt.containingFile?.virtualFile ?: return@GutterIconNavigationHandler
        val manager = FileEditorManager.getInstance(project)

        manager.openFile(file, true)
        manager.setSelectedEditor(file, MIXIN_EDITOR_ID)

        val parent = elt.parent
        if (parent is PsiMethod) {
            val targetMethodName = extractTargetMethodName(parent)
            if (targetMethodName != null) {
                val editors = manager.getEditors(file)
                val editor = editors.find { it is MixinPreviewEditor } as? MixinPreviewEditor
                editor?.scrollToMethod(targetMethodName)
            }
        }
    }

    private fun isMixinClass(psiClass: PsiClass): Boolean {
        return psiClass.getAnnotation("$MIXIN_PACKAGE.Mixin") != null
    }

    private fun hasMixinAnnotation(method: PsiMethod): Boolean {
        val anns = method.modifierList.annotations
        return anns.any { it.qualifiedName?.startsWith(MIXIN_PACKAGE) == true }
    }

    private fun extractTargetMethodName(method: PsiMethod): String? {
        val annotations = method.modifierList.annotations
        for (ann in annotations) {
            val qName = ann.qualifiedName ?: continue
            if (!qName.startsWith(MIXIN_PACKAGE)) continue

            if (qName.endsWith(".Overwrite")) {
                return method.name
            }

            val methodAttr = ann.findAttributeValue("method")
            val resolved = resolveAnnotationValue(methodAttr)

            if (!resolved.isNullOrEmpty()) {
                return resolved
            }
        }
        return null
    }

    private fun resolveAnnotationValue(value: PsiAnnotationMemberValue?): String? {
        if (value is PsiLiteralExpression) {
            return value.value as? String
        }
        if (value is PsiArrayInitializerMemberValue) {
            val first = value.initializers.firstOrNull()
            if (first is PsiLiteralExpression) {
                return first.value as? String
            }
        }
        return null
    }
}