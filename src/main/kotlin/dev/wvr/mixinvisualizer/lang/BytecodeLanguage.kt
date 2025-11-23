package dev.wvr.mixinvisualizer.lang

import com.intellij.lang.Language

object BytecodeLanguage : Language("JVM Bytecode") {
    private fun readResolve(): Any = BytecodeLanguage
}