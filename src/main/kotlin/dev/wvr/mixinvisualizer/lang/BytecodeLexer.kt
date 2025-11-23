package dev.wvr.mixinvisualizer.lang

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerPosition
import com.intellij.psi.tree.IElementType
import com.intellij.psi.TokenType

class BytecodeLexer : Lexer() {
    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var currentState = 0
    private var currentTokenStart = 0
    private var currentTokenEnd = 0
    private var currentTokenType: IElementType? = null

    private val COMMENT_PATTERN = Regex("^//.*")
    private val STRING_PATTERN = Regex("^\"([^\\\\\"]|\\\\.)*\"")
    private val NUMBER_PATTERN = Regex("^-?\\d+(\\.\\d+)?")
    private val DIRECTIVE_PATTERN = Regex("^\\.[a-zA-Z_]+") // .class, .super
    private val LABEL_PATTERN = Regex("^L\\d+")
    private val IDENTIFIER_PATTERN = Regex("^[a-zA-Z_$<][a-zA-Z0-9_$<>/;]*")
    private val WHITESPACE_PATTERN = Regex("^\\s+")
    private val SYMBOL_PATTERN = Regex("^[:=,;@()\\[\\]/.\\-+]")

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.currentState = initialState
        this.currentTokenEnd = startOffset
        advance()
    }

    override fun getState() = currentState
    override fun getTokenType() = currentTokenType
    override fun getTokenStart() = currentTokenStart
    override fun getTokenEnd() = currentTokenEnd

    override fun advance() {
        currentTokenStart = currentTokenEnd
        if (currentTokenStart >= endOffset) {
            currentTokenType = null
            return
        }

        val text = buffer.subSequence(currentTokenStart, endOffset)

        val ws = WHITESPACE_PATTERN.find(text)
        if (ws != null) {
            currentTokenType = TokenType.WHITE_SPACE
            currentTokenEnd += ws.value.length
            return
        }

        val comment = COMMENT_PATTERN.find(text)
        if (comment != null) {
            currentTokenType = BytecodeTokenTypes.COMMENT
            currentTokenEnd += comment.value.length
            return
        }

        val str = STRING_PATTERN.find(text)
        if (str != null) {
            currentTokenType = BytecodeTokenTypes.STRING
            currentTokenEnd += str.value.length
            return
        }

        val num = NUMBER_PATTERN.find(text)
        if (num != null) {
            currentTokenType = BytecodeTokenTypes.NUMBER
            currentTokenEnd += num.value.length
            return
        }

        val dir = DIRECTIVE_PATTERN.find(text)
        if (dir != null) {
            currentTokenType = BytecodeTokenTypes.KEYWORD
            currentTokenEnd += dir.value.length
            return
        }

        val lbl = LABEL_PATTERN.find(text)
        if (lbl != null) {
            currentTokenType = BytecodeTokenTypes.LABEL
            currentTokenEnd += lbl.value.length
            return
        }

        val id = IDENTIFIER_PATTERN.find(text)
        if (id != null) {
            val valStr = id.value
            currentTokenType = if (valStr == valStr.uppercase() && valStr.length > 1 && !valStr.contains("/")) {
                BytecodeTokenTypes.OPCODE
            } else if (valStr == "public" || valStr == "private" || valStr == "protected" || valStr == "static" || valStr == "final") {
                BytecodeTokenTypes.KEYWORD
            } else {
                BytecodeTokenTypes.IDENTIFIER
            }
            currentTokenEnd += valStr.length
            return
        }

        val sym = SYMBOL_PATTERN.find(text)
        if (sym != null) {
            currentTokenType = BytecodeTokenTypes.SYMBOL
            currentTokenEnd += sym.value.length
            return
        }

        currentTokenType = BytecodeTokenTypes.BAD_CHARACTER
        currentTokenEnd++
    }

    override fun getBufferSequence() = buffer
    override fun getBufferEnd() = endOffset
    override fun getCurrentPosition(): LexerPosition = object : LexerPosition {
        override fun getOffset() = currentTokenStart
        override fun getState() = currentState
    }
    override fun restore(position: LexerPosition) {
        start(buffer, position.offset, endOffset, position.state)
    }
}