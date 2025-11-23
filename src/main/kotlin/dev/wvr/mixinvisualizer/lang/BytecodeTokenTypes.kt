package dev.wvr.mixinvisualizer.lang

import com.intellij.psi.tree.IElementType

object BytecodeTokenTypes {
    class BytecodeElementType(debugName: String) : IElementType(debugName, BytecodeLanguage)

    val KEYWORD = BytecodeElementType("KEYWORD")
    val OPCODE = BytecodeElementType("OPCODE")
    val IDENTIFIER = BytecodeElementType("IDENTIFIER")
    val NUMBER = BytecodeElementType("NUMBER")
    val STRING = BytecodeElementType("STRING")
    val LABEL = BytecodeElementType("LABEL")
    val COMMENT = BytecodeElementType("COMMENT")
    val BAD_CHARACTER = BytecodeElementType("BAD_CHARACTER")
    val SYMBOL = BytecodeElementType("SYMBOL")
}