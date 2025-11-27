package dev.wvr.mixinvisualizer.ui

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.*
import com.intellij.util.Function

class MixinLineMarkerProvider : LineMarkerProvider {
    companion object {
        const val MIXIN_EDITOR_ID = "mixin-vis-editor"
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Preview,
            TOOLTIP_PROVIDER,
            NAVIGATION_HANDLER,
            GutterIconRenderer.Alignment.RIGHT
        ) { "Mixin Visualizer" }
    }
}

private val TOOLTIP_PROVIDER = Function<PsiElement, String> { element ->
    when (val parent = element.parent) {
        is PsiClass -> if (hasAnnotation(parent, "Mixin")) "Preview Mixin Result" else null
        is PsiMethod -> if (hasAnnotation(
                parent,
                "Inject",
                "Redirect",
                "Overwrite"
            )
        ) "Show in Mixin Visualizer" else null

        else -> null
    }
}

private val NAVIGATION_HANDLER = GutterIconNavigationHandler<PsiElement> { _, elt ->
    val project = elt.project
    val file = elt.containingFile?.virtualFile ?: return@GutterIconNavigationHandler
    val manager = FileEditorManager.getInstance(project)

    manager.openFile(file, true)
    manager.setSelectedEditor(file, MixinLineMarkerProvider.MIXIN_EDITOR_ID)

    val parent = elt.parent
    if (parent is PsiMethod) {
        val targetMethodName = extractTargetMethodName(parent)

        if (targetMethodName != null) {
            val editor = manager.getSelectedEditor(file)
            if (editor is MixinPreviewEditor) {
                editor.scrollToMethod(targetMethodName)
            }
        }
    }
}

private fun extractTargetMethodName(method: PsiMethod): String? {
    val annotations = method.modifierList.annotations
    for (ann in annotations) {
        val qName = ann.qualifiedName ?: continue

        if (qName.contains("Inject") || qName.contains("Redirect")) {
            val methodAttr = ann.findAttributeValue("method")
            val value = resolveAnnotationValue(methodAttr)
            if (!value.isNullOrEmpty()) return value
        }

        if (qName.contains("Overwrite")) {
            return method.name
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

private fun hasAnnotation(owner: PsiModifierListOwner, vararg names: String): Boolean {
    return owner.modifierList?.annotations?.any { ann ->
        val qName = ann.qualifiedName ?: ""
        names.any { qName.contains(it) }
    } ?: false
}