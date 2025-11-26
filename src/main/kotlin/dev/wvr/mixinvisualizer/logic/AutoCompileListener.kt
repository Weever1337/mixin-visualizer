package dev.wvr.mixinvisualizer.logic

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import dev.wvr.mixinvisualizer.settings.MixinVisualizerSettings
import java.util.concurrent.atomic.AtomicBoolean

class AutoCompileListener : FileDocumentManagerListener {
    private object CompilationManager {
        private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, null)
        private val isCompiling = AtomicBoolean(false)
        private var pendingRecompile = false

        fun requestCompilation(project: Project, file: VirtualFile) {
            alarm.cancelAllRequests()
            alarm.addRequest({
                triggerCompile(project, file)
            }, 500, ModalityState.nonModal())
        }

        private fun triggerCompile(project: Project, file: VirtualFile) {
            if (project.isDisposed) return

            if (isCompiling.get()) {
                pendingRecompile = true
                return
            }

            val module = ModuleUtil.findModuleForFile(file, project) ?: return

            isCompiling.set(true)
            pendingRecompile = false

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    isCompiling.set(false)
                    return@invokeLater
                }

                CompilerManager.getInstance(project).compile(module, CompileStatusNotification { aborted, errors, _, _ ->
                    isCompiling.set(false)

                    if (!aborted && errors == 0) {
                        project.messageBus.syncPublisher(MixinCompilationTopic.TOPIC).onCompilationFinished()
                    }

                    if (pendingRecompile) {
                        alarm.addRequest({ requestCompilation(project, file) }, 200)
                    }
                })
            }
        }
    }

    override fun beforeDocumentSaving(document: Document) {
        if (!MixinVisualizerSettings.instance.state.autoCompileOnSave) return

        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        if (!isRelevantFile(file)) return
        if (!isMixinRelated(document)) return

        for (project in ProjectManager.getInstance().openProjects) {
            if (ModuleUtil.findModuleForFile(file, project) != null) {
                CompilationManager.requestCompilation(project, file)
                break
            }
        }
    }

    private fun isRelevantFile(file: VirtualFile): Boolean {
        return file.extension == "java" || file.extension == "kt"
    }

    private fun isMixinRelated(document: Document): Boolean {
        val text = document.charsSequence
        return text.contains("@Mixin") || text.contains("org.spongepowered.asm.mixin")
    }
}