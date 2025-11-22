package dev.wvr.mixinvisualizer.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod

// TODO: fix line marker provider, becuase doesnt work properly
class MixinLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val parent = element.parent

        val tooltip = when (parent) {
            is PsiClass -> if (hasAnnotation(parent, "Mixin")) "Preview Mixin Result" else null
            is PsiMethod -> if (hasAnnotation(parent, "Inject", "Redirect", "Overwrite")) "Show in Mixin Visualizer" else null
            else -> null
        } ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Preview,
            { tooltip },
            { _, elt ->
                val file = elt.containingFile?.virtualFile
                if (file != null) {
                    val mgr = FileEditorManager.getInstance(elt.project)
                    mgr.openFile(file, true)
                }
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "Mixin Visualizer" }
        )
    }

    private fun hasAnnotation(owner: com.intellij.psi.PsiModifierListOwner, vararg names: String): Boolean {
        return owner.modifierList?.annotations?.any { ann ->
            val qName = ann.qualifiedName ?: ""
            names.any { qName.contains(it) }
        } ?: false
    }
}