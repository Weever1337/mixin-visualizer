package dev.wvr.mixinvisualizer.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.ui.components.JBLoadingPanel
import dev.wvr.mixinvisualizer.lang.BytecodeFileType
import dev.wvr.mixinvisualizer.logic.MixinCompilationTopic
import dev.wvr.mixinvisualizer.logic.MixinProcessor
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.openapi.project.DumbService

class MixinPreviewEditor(
    private val project: Project,
    private val file: VirtualFile
) : FileEditor {

    private val panel = JPanel(BorderLayout())
    private val loadingPanel = JBLoadingPanel(BorderLayout(), this)
    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, this, null)

    private val processor = MixinProcessor(project)

    private var showBytecode = false

    init {
        loadingPanel.add(diffPanel.component, BorderLayout.CENTER)
        panel.add(createToolbar(), BorderLayout.NORTH)
        panel.add(loadingPanel, BorderLayout.CENTER)

        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childrenChanged(event: PsiTreeChangeEvent) {
                if (event.file?.virtualFile == file) {
                    refresh()
                }
            }
        }, this)

        val connection = project.messageBus.connect(this)
        connection.subscribe(MixinCompilationTopic.TOPIC, object : MixinCompilationTopic {
            override fun onCompilationFinished() {
                ApplicationManager.getApplication().invokeLater {
                    refresh()
                }
            }
        })

        refresh()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()

        val refreshAction = object : AnAction("Refresh", "Reload Mixin changes", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        }

        val toggleAction = object : ToggleAction("Show Bytecode", "Toggle between Java and ASM Bytecode view", AllIcons.FileTypes.JavaClass) {
            override fun isSelected(e: AnActionEvent) = showBytecode
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showBytecode = state
                refresh()
            }
        }

        group.add(refreshAction)
        group.add(toggleAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("MixinVisualizerToolbar", group, true)
        toolbar.targetComponent = diffPanel.component
        return toolbar.component
    }

    private fun refresh() {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return

        loadingPanel.startLoading()

        ApplicationManager.getApplication().executeOnPooledThread {
            val (orig, trans) = DumbService.getInstance(project).runReadActionInSmartMode<Pair<String, String>> {
                processor.process(psi, showBytecode)
            }

            ApplicationManager.getApplication().invokeLater {
                updateDiff(orig, trans)
                loadingPanel.stopLoading()
            }
        }
    }

    private fun updateDiff(original: String, transformed: String) {
        if (Disposer.isDisposed(diffPanel)) return

        val factory = DiffContentFactory.getInstance()

        val fileType = if (showBytecode)
            BytecodeFileType
        else
            JavaFileType.INSTANCE

        val c1 = factory.create(project, original, fileType)
        val c2 = factory.create(project, transformed, fileType)

        val request = SimpleDiffRequest("Mixin Diff", c1, c2, "Target (Original)", "Target (Injected)")
        diffPanel.setRequest(request)
    }

    override fun getFile() = file
    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent() = diffPanel.preferredFocusedComponent
    override fun getName() = "Mixin Preview"
    override fun setState(s: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(l: PropertyChangeListener) {}
    override fun removePropertyChangeListener(l: PropertyChangeListener) {}
    override fun getCurrentLocation() = null
    override fun getBackgroundHighlighter() = null
    override fun dispose() { Disposer.dispose(diffPanel) }
    override fun <T> getUserData(key: com.intellij.openapi.util.Key<T>) = null
    override fun <T> putUserData(key: com.intellij.openapi.util.Key<T>, v: T?) {}
}