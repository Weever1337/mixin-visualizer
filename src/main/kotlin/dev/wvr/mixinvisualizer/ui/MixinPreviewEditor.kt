package dev.wvr.mixinvisualizer.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.task.ProjectTaskListener
import com.intellij.task.ProjectTaskManager
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.util.Alarm
import dev.wvr.mixinvisualizer.lang.BytecodeFileType
import dev.wvr.mixinvisualizer.logic.MixinCompilationTopic
import dev.wvr.mixinvisualizer.logic.MixinProcessor
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JPanel

class MixinPreviewEditor(
    private val project: Project,
    private val file: VirtualFile
) : FileEditor {
    private val panel = JPanel(BorderLayout())
    private val loadingPanel = JBLoadingPanel(BorderLayout(), this)
    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, this, null)

    private val processor = MixinProcessor(project)

    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var isVisible = false
    private var isDirty = true

    private var showBytecode = false
    private var applyAllMixins = false
    private var compactDiff = false

    private var pendingScrollTarget: String? = null
    private var currentResultDocument: Document? = null

    private val refreshGeneration = AtomicLong(0)
    private var lastShownContent: Pair<String, String>? = null
    private var lastShownMode: Triple<Boolean, Boolean, Boolean>? = null

    init {
        loadingPanel.add(diffPanel.component, BorderLayout.CENTER)
        panel.add(createToolbar(), BorderLayout.NORTH)
        panel.add(loadingPanel, BorderLayout.CENTER)

        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childrenChanged(event: PsiTreeChangeEvent) {
                if (event.file?.virtualFile == file) {
                    scheduleRefresh()
                }
            }
        }, this)

        val connection = project.messageBus.connect(this)
        connection.subscribe(MixinCompilationTopic.TOPIC, object : MixinCompilationTopic {
            override fun onCompilationFinished() {
                scheduleRefresh(immediate = true)
            }
        })
        connection.subscribe(ProjectTaskListener.TOPIC, object : ProjectTaskListener {
            override fun finished(result: ProjectTaskManager.Result) {
                if (!result.isAborted && !result.hasErrors()) scheduleRefresh(immediate = true)
            }
        })

        scheduleRefresh(immediate = true)
    }

    private fun scheduleRefresh(immediate: Boolean = false) {
        if (project.isDisposed) return

        isDirty = true
        updateAlarm.cancelAllRequests()

        val delay = if (immediate) 0 else 500

        updateAlarm.addRequest({
            if (isVisible && !project.isDisposed) {
                refresh()
            }
        }, delay, ModalityState.defaultModalityState())
    }

    override fun selectNotify() {
        isVisible = true
        if (isDirty) {
            scheduleRefresh(immediate = true)
        }
    }

    override fun deselectNotify() {
        isVisible = false
    }

    fun scrollToMethod(methodName: String) {
        this.pendingScrollTarget = methodName
        performScroll()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()

        val refreshAction = object : AnAction("Refresh", "Reload Mixin changes", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                scheduleRefresh(immediate = true)
            }
        }

        val toggleAction = object :
            ToggleAction("Show Bytecode", "Toggle between Java and ASM Bytecode view", AllIcons.FileTypes.JavaClass) {
            override fun isSelected(e: AnActionEvent) = showBytecode
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showBytecode = state
                scheduleRefresh(immediate = true)
            }
        }

        val allMixinsAction = object :
            ToggleAction("All Mixins", "Apply every project mixin targeting this class (priority order)", AllIcons.Actions.GroupBy) {
            override fun isSelected(e: AnActionEvent) = applyAllMixins
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                applyAllMixins = state
                scheduleRefresh(immediate = true)
            }
        }

        val compactAction = object :
            ToggleAction("Compact Diff", "Show only the methods affected by the mixin", AllIcons.Actions.Collapseall) {
            override fun isSelected(e: AnActionEvent) = compactDiff
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                compactDiff = state
                scheduleRefresh(immediate = true)
            }
        }

        group.add(refreshAction)
        group.add(toggleAction)
        group.add(allMixinsAction)
        group.add(compactAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("MixinVisualizerToolbar", group, true)
        toolbar.targetComponent = diffPanel.component
        return toolbar.component
    }

    private fun refresh() {
        if (project.isDisposed || !file.isValid) return

        isDirty = false
        loadingPanel.startLoading()

        val generation = refreshGeneration.incrementAndGet()
        val bytecodeMode = showBytecode
        val allMixins = applyAllMixins
        val compact = compactDiff

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed || generation != refreshGeneration.get()) return@executeOnPooledThread

            val result = processor.process(file, bytecodeMode, allMixins, compact)

            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                if (generation != refreshGeneration.get()) return@invokeLater

                val mode = Triple(bytecodeMode, allMixins, compact)
                if (result != lastShownContent || mode != lastShownMode) {
                    lastShownContent = result
                    lastShownMode = mode
                    updateDiff(result.first, result.second)
                } else {
                    performScroll()
                }
                loadingPanel.stopLoading()
            }, ModalityState.defaultModalityState())
        }
    }

    private fun updateDiff(original: String, transformed: String) {
        if (Disposer.isDisposed(diffPanel)) return

        val factory = DiffContentFactory.getInstance()
        val fileType = if (showBytecode) BytecodeFileType else JavaFileType.INSTANCE

        val content1 = factory.create(project, original, fileType)
        val content2 = factory.create(project, transformed, fileType)

        this.currentResultDocument = content2.document

        val injectedTitle = if (applyAllMixins) "Target (All Mixins Injected)" else "Target (Injected)"
        val request = SimpleDiffRequest("Mixin Diff", content1, content2, "Target (Original)", injectedTitle)
        diffPanel.setRequest(request)

        performScroll()
    }

    private fun performScroll() {
        val targetName = pendingScrollTarget ?: return
        val doc = currentResultDocument ?: return

        val text = doc.charsSequence
        val name = when (targetName) {
            "<init>" -> Regex("\\bclass (\\w+)").find(text)?.groupValues?.get(1) ?: targetName
            "<clinit>" -> "static {"
            else -> targetName
        }
        var idx = text.indexOf(" $name(")
        if (idx == -1) idx = text.indexOf(" $name ")
        if (idx == -1) idx = text.indexOf(name)

        if (idx != -1) {
            val editors = EditorFactory.getInstance().getEditors(doc, project)
            for (editor in editors) {
                if (!editor.isDisposed) {
                    val offset = idx
                    val line = doc.getLineNumber(offset)

                    editor.caretModel.moveToOffset(offset)
                    editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.CENTER)

                    editor.selectionModel.setSelection(offset, offset + name.length)
                }
            }
        }

        pendingScrollTarget = null
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
    override fun dispose() {
        Disposer.dispose(diffPanel)
    }

    override fun <T> getUserData(key: Key<T>) = null
    override fun <T> putUserData(key: Key<T>, v: T?) {}
}