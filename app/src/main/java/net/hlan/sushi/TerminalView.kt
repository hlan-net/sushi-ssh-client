package net.hlan.sushi

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatTextView
import java.util.regex.Pattern

class TerminalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var currentFgColor: Int? = null
    private var currentBgColor: Int? = null
    private val rawTextBuffer = StringBuilder()
    private var pendingCarriageReturn = false
    var onInputText: ((String) -> Unit)? = null
    var renderAnsi: Boolean = true

    var onSizeChangedListener: ((col: Int, row: Int, wp: Int, hp: Int) -> Unit)? = null

    companion object {
        private const val MAX_LINES = 500
        private const val MAX_CHARS = 200_000
        private val ESCAPE_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[a-ln-zA-LN-Z]")
        private val SGR_PATTERN = Pattern.compile("\u001B\\[([0-9;]*)m")
    }

    init {
        typeface = android.graphics.Typeface.MONOSPACE
        movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
        isFocusable = true
        isFocusableInTouchMode = true
        setTextIsSelectable(true)
        setOnClickListener {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) {
                    val normalized = when (value) {
                        "\n", "\r\n" -> "\n"
                        else -> value.replace("\r", "").replace("\n", "")
                    }
                    if (normalized.isNotEmpty()) {
                        onInputText?.invoke(normalized)
                    }
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return true
                }
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER -> onInputText?.invoke("\n")
                    KeyEvent.KEYCODE_TAB -> onInputText?.invoke("\t")
                    KeyEvent.KEYCODE_DEL -> onInputText?.invoke("\b")
                    else -> Unit
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        onInputText?.invoke("\b")
                    }
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val charWidth = paint.measureText("W")
        val fontMetrics = paint.fontMetrics
        val charHeight = fontMetrics.descent - fontMetrics.ascent

        val availableWidth = w - paddingLeft - paddingRight
        val availableHeight = h - paddingTop - paddingBottom

        val cols = if (charWidth > 0) (availableWidth / charWidth).toInt() else 0
        val rows = if (charHeight > 0) (availableHeight / charHeight).toInt() else 0

        if (cols > 0 && rows > 0) {
            onSizeChangedListener?.invoke(cols, rows, w, h)
        }
    }

    fun appendLog(text: String) {
        // Prevent DoS from extremely large input strings by truncating
        val safeText = if (text.length > 50000) text.substring(text.length - 50000) else text
        val normalizedText = normalizeLineEndings(safeText)
        rawTextBuffer.append(normalizedText)
        trimBuffer()
        updateText()
    }

    fun clearLog() {
        rawTextBuffer.setLength(0)
        currentFgColor = null
        currentBgColor = null
        pendingCarriageReturn = false
        text = ""
        scrollTo(0, 0)
    }

    private fun updateText() {
        val fullText = rawTextBuffer.toString()
        currentFgColor = null
        currentBgColor = null
        text = runCatching {
            if (renderAnsi) parseAnsi(fullText) else fullText
        }.getOrElse {
            fullText
        }
        post {
            val scrollAmount = layout?.let {
                it.getLineTop(lineCount) - height + paddingBottom + paddingTop
            } ?: 0
            if (scrollAmount > 0) {
                scrollTo(0, scrollAmount)
            } else {
                scrollTo(0, 0)
            }
        }
    }

    private fun trimBuffer() {
        if (rawTextBuffer.length > MAX_CHARS) {
            val extraChars = rawTextBuffer.length - MAX_CHARS
            rawTextBuffer.delete(0, extraChars)
        }

        var newlineCount = 0
        for (i in 0 until rawTextBuffer.length) {
            if (rawTextBuffer[i] == '\n') {
                newlineCount++
            }
        }

        if (newlineCount <= MAX_LINES) {
            return
        }

        var linesToDrop = newlineCount - MAX_LINES
        var cutIndex = 0
        while (linesToDrop > 0 && cutIndex < rawTextBuffer.length) {
            if (rawTextBuffer[cutIndex] == '\n') {
                linesToDrop--
            }
            cutIndex++
        }

        if (cutIndex > 0) {
            rawTextBuffer.delete(0, cutIndex)
        }
    }

    private fun normalizeLineEndings(input: String): String {
        if (input.isEmpty()) {
            return input
        }

        val out = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '\r' -> pendingCarriageReturn = true
                '\n' -> {
                    out.append('\n')
                    pendingCarriageReturn = false
                }
                else -> {
                    pendingCarriageReturn = false
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }

    private fun parseAnsi(rawText: String): CharSequence {
        // Strip out non-color ANSI escape sequences (e.g. cursor movements)
        val text = ESCAPE_PATTERN.matcher(rawText).replaceAll("")

        val builder = SpannableStringBuilder()
        val matcher = SGR_PATTERN.matcher(text)

        var lastEnd = 0

        while (matcher.find()) {
            val start = matcher.start()
            if (start > lastEnd) {
                val spanStart = builder.length
                builder.append(text.substring(lastEnd, start))
                applyColors(builder, spanStart, builder.length, currentFgColor, currentBgColor)
            }

            val codesStr = matcher.group(1)
            // Limit the number of codes processed to prevent DoS from crafted payloads
            val codes = if (codesStr.isNullOrEmpty()) listOf(0) else codesStr.split(';').take(10).mapNotNull { it.toIntOrNull() }

            for (code in codes) {
                when (code) {
                    0 -> { currentFgColor = null; currentBgColor = null }
                    30 -> currentFgColor = Color.BLACK
                    31 -> currentFgColor = Color.RED
                    32 -> currentFgColor = Color.GREEN
                    33 -> currentFgColor = Color.YELLOW
                    34 -> currentFgColor = Color.BLUE
                    35 -> currentFgColor = Color.MAGENTA
                    36 -> currentFgColor = Color.CYAN
                    37 -> currentFgColor = Color.WHITE
                    39 -> currentFgColor = null
                    40 -> currentBgColor = Color.BLACK
                    41 -> currentBgColor = Color.RED
                    42 -> currentBgColor = Color.GREEN
                    43 -> currentBgColor = Color.YELLOW
                    44 -> currentBgColor = Color.BLUE
                    45 -> currentBgColor = Color.MAGENTA
                    46 -> currentBgColor = Color.CYAN
                    47 -> currentBgColor = Color.WHITE
                    49 -> currentBgColor = null
                    90 -> currentFgColor = Color.DKGRAY
                    91 -> currentFgColor = Color.RED
                    92 -> currentFgColor = Color.GREEN
                    93 -> currentFgColor = Color.YELLOW
                    94 -> currentFgColor = Color.BLUE
                    95 -> currentFgColor = Color.MAGENTA
                    96 -> currentFgColor = Color.CYAN
                    97 -> currentFgColor = Color.WHITE
                }
            }
            lastEnd = matcher.end()
        }

        if (lastEnd < text.length) {
            val spanStart = builder.length
            builder.append(text.substring(lastEnd))
            applyColors(builder, spanStart, builder.length, currentFgColor, currentBgColor)
        }

        return builder
    }

    private fun applyColors(builder: SpannableStringBuilder, start: Int, end: Int, fg: Int?, bg: Int?) {
        if (start == end) return
        fg?.let { builder.setSpan(ForegroundColorSpan(it), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        bg?.let { builder.setSpan(BackgroundColorSpan(it), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
