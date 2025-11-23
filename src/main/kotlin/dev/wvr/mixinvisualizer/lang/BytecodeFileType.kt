package dev.wvr.mixinvisualizer.lang

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object BytecodeFileType : LanguageFileType(BytecodeLanguage) {
    override fun getName() = "JVM Bytecode"
    override fun getDescription() = "JVM bytecode trace"
    override fun getDefaultExtension() = "jvm"
    override fun getIcon(): Icon = AllIcons.FileTypes.JavaClass
}