package net.hlan.sushi

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import java.util.LinkedList
import java.util.regex.Pattern

class TerminalView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var currentFgColor: Int? = null
    private var currentBgColor: Int? = null
    private val lineBuffer = LinkedList<CharSequence>()

    var onSizeChangedListener: ((col: Int, row: Int, wp: Int, hp: Int) -> Unit)? = null

    companion object {
        private const val MAX_LINES = 500
        private val ESCAPE_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[a-ln-zA-LN-Z]")
        private val SGR_PATTERN = Pattern.compile("\u001B\\[([0-9;]*)m")
    }

    init {
        typeface = android.graphics.Typeface.MONOSPACE
        movementMethod = android.text.method.ScrollingMovementMethod.getInstance()
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
        val lines = safeText.split('\n')
        
        // Process at most MAX_LINES to avoid memory exhaustion
        val linesToProcess = if (lines.size > MAX_LINES) lines.takeLast(MAX_LINES) else lines

        for (line in linesToProcess) {
            val parsed = parseAnsi(line)
            lineBuffer.add(parsed)
            if (lineBuffer.size > MAX_LINES) {
                lineBuffer.removeFirst()
            }
        }
        updateText()
    }

    fun clearLog() {
        lineBuffer.clear()
        text = ""
        scrollTo(0, 0)
    }

    private fun updateText() {
        val builder = SpannableStringBuilder()
        for (i in 0 until lineBuffer.size) {
            builder.append(lineBuffer[i])
            if (i < lineBuffer.size - 1) {
                builder.append("\n")
            }
        }
        text = builder
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
