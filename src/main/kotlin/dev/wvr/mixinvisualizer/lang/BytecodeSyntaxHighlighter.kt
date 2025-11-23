package dev.wvr.mixinvisualizer.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

class BytecodeSyntaxHighlighter : SyntaxHighlighterBase() {
    companion object {
        val KEYWORD = TextAttributesKey.createTextAttributesKey("BYTECODE_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        val OPCODE = TextAttributesKey.createTextAttributesKey("BYTECODE_OPCODE", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION)
        val NUMBER = TextAttributesKey.createTextAttributesKey("BYTECODE_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val STRING = TextAttributesKey.createTextAttributesKey("BYTECODE_STRING", DefaultLanguageHighlighterColors.STRING)
        val COMMENT = TextAttributesKey.createTextAttributesKey("BYTECODE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val LABEL = TextAttributesKey.createTextAttributesKey("BYTECODE_LABEL", DefaultLanguageHighlighterColors.LABEL)
        val SYMBOL = TextAttributesKey.createTextAttributesKey("BYTECODE_SYMBOL", DefaultLanguageHighlighterColors.OPERATION_SIGN)
        val BAD = TextAttributesKey.createTextAttributesKey("BYTECODE_BAD", HighlighterColors.BAD_CHARACTER)
    }

    override fun getHighlightingLexer(): Lexer = BytecodeLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        return when (tokenType) {
            BytecodeTokenTypes.KEYWORD -> arrayOf(KEYWORD)
            BytecodeTokenTypes.OPCODE -> arrayOf(OPCODE)
            BytecodeTokenTypes.NUMBER -> arrayOf(NUMBER)
            BytecodeTokenTypes.STRING -> arrayOf(STRING)
            BytecodeTokenTypes.COMMENT -> arrayOf(COMMENT)
            BytecodeTokenTypes.LABEL -> arrayOf(LABEL)
            BytecodeTokenTypes.SYMBOL -> arrayOf(SYMBOL)
            BytecodeTokenTypes.BAD_CHARACTER -> arrayOf(BAD)
            else -> emptyArray()
        }
    }
}

class BytecodeSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return BytecodeSyntaxHighlighter()
    }
}