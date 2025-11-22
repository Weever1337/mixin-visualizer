package dev.wvr.mixinvisualizer.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

class MixinPreviewFileEditorProvider : FileEditorProvider {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.extension != "java") return false
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return false
        val txt = psiFile.text
        return txt.contains("@Mixin") || txt.contains("org.spongepowered.asm.mixin")
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return MixinPreviewEditor(project, file)
    }

    override fun getEditorTypeId() = "mixin-vis-editor"
    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}