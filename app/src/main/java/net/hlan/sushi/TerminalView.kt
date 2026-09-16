package net.hlan.sushi

import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.text.Selection
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
    private var oscState = OscState.NONE
    private var oscLength = 0
    var onInputText: ((String) -> Unit)? = null
    var renderAnsi: Boolean = true

    // OSC sequences (ESC ] ... BEL/ST) can be split across network chunks,
    // so the filter state must persist between appendLog calls.
    private enum class OscState { NONE, ESC_SEEN, IN_OSC, IN_OSC_ESC_SEEN }

    private var ansiPalette: IntArray? = null

    var onSizeChangedListener: ((col: Int, row: Int, wp: Int, hp: Int) -> Unit)? = null

    companion object {
        /**
         * Cursor keys as xterm sends them with the cursor keypad in normal mode, which is what
         * readline expects for history. The app never enables DECCKM (`ESC [ ? 1 h`), so the
         * application-keypad forms (`ESC O A`) would not be the right thing to send.
         */
        const val CURSOR_UP = "\u001B[A"
        const val CURSOR_DOWN = "\u001B[B"

        /** Where the bright half of the palette starts, so 90-97 map past 30-37. */
        private const val BRIGHT_OFFSET = 8

        /** Normal 0-7 then bright 8-15, in ANSI order: black, red, green, yellow, blue, magenta, cyan, white. */
        internal val ANSI_COLOR_RES = intArrayOf(
            R.color.sushi_ansi_black, R.color.sushi_ansi_red,
            R.color.sushi_ansi_green, R.color.sushi_ansi_yellow,
            R.color.sushi_ansi_blue, R.color.sushi_ansi_magenta,
            R.color.sushi_ansi_cyan, R.color.sushi_ansi_white,
            R.color.sushi_ansi_bright_black, R.color.sushi_ansi_bright_red,
            R.color.sushi_ansi_bright_green, R.color.sushi_ansi_bright_yellow,
            R.color.sushi_ansi_bright_blue, R.color.sushi_ansi_bright_magenta,
            R.color.sushi_ansi_bright_cyan, R.color.sushi_ansi_bright_white
        )

        private const val MAX_LINES = 500
        private const val MAX_CHARS = 200_000
        // Unterminated OSC guard: a missing BEL/ST must not swallow output forever.
        private const val MAX_OSC_LENGTH = 2048
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

    override fun onTextContextMenuItem(id: Int): Boolean {
        when (id) {
            android.R.id.paste -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = clipboard?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text
                    if (!text.isNullOrEmpty()) {
                        onInputText?.invoke(text.toString())
                    }
                }
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    /**
     * The keys a terminal owns rather than the text view, mapped once for both paths that can
     * deliver them: an IME's [InputConnection.sendKeyEvent] and [onKeyDown] for anything
     * dispatched to the view itself.
     */
    private fun shellInputFor(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> "\n"
        KeyEvent.KEYCODE_TAB -> "\t"
        KeyEvent.KEYCODE_DEL -> "\b"
        KeyEvent.KEYCODE_DPAD_UP -> CURSOR_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> CURSOR_DOWN
        else -> null
    }

    /**
     * A physical keyboard does not go through the input connection: its events are dispatched to
     * the focused view, and this one is a selectable [AppCompatTextView] with a movement method,
     * which answers the arrows by scrolling the log or walking the selection. Both swallow the
     * key before the shell ever sees it, so the terminal's own keys are claimed here first.
     *
     * Only while a shell is attached — with no [onInputText] the view is a log, and scrolling it
     * with the arrows is the right behaviour.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val send = onInputText
        val input = shellInputFor(keyCode)
        if (send != null && input != null) {
            send(input)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
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
                shellInputFor(event.keyCode)?.let { onInputText?.invoke(it) }
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
        for (ch in safeText) {
            processChar(ch)
        }
        val dropped = trimBuffer()
        updateText(dropped)
    }

    /**
     * Appends one of the app's own status lines, as opposed to [appendLog]'s stream of remote
     * output.
     *
     * Status strings carry no newline and [appendLog] only breaks a line when it sees a real
     * `\n`, so each status used to continue whatever was on screen — and the shell prompt that
     * arrived next continued it in turn, producing
     * `[Terminal] Connecting...Connected to ekho (larry@…) · ssh.larry@ekho:~ $` on one line.
     *
     * The text is put on a line of its own and terminated, adding neither a leading blank line
     * when the buffer already sits at the start of one nor a trailing one when the text ends in a
     * newline itself. Remote output keeps going through [appendLog], whose line discipline is the
     * remote's own.
     */
    fun appendLogLine(text: String) {
        if (rawTextBuffer.isNotEmpty() && !rawTextBuffer.endsWith("\n")) {
            appendLog("\n")
        }
        appendLog(if (text.endsWith("\n")) text else text + "\n")
    }

    /**
     * ANSI colour [index] (0-7 normal, 8-15 bright) in the palette for the current theme.
     *
     * These were the raw `Color` constants, which suit a terminal that is always dark. The
     * terminal now follows the system theme, and on a light background pure yellow reads at
     * 1.1:1 and white at 1.1:1 — invisible. Both palettes live in colors.xml, where the light one
     * clears 4.5:1 throughout and the dark one does too apart from ANSI black, the dim colour.
     *
     * Resolved lazily and cached: a theme change recreates the activity, and with it this view.
     */
    private fun ansiColor(index: Int): Int {
        ansiPalette?.let { return it[index] }
        val resolved = IntArray(ANSI_COLOR_RES.size) { context.getColor(ANSI_COLOR_RES[it]) }
        ansiPalette = resolved
        return resolved[index]
    }

    fun getRawText(): String = rawTextBuffer.toString()

    fun clearLog() {
        rawTextBuffer.setLength(0)
        currentFgColor = null
        currentBgColor = null
        pendingCarriageReturn = false
        text = ""
        scrollTo(0, 0)
    }

    private fun updateText(renderedCharsDropped: Int = 0) {
        val selStart = selectionStart
        val selEnd = selectionEnd
        val hasSelection = selStart >= 0 && selEnd >= 0 && selStart != selEnd

        val fullText = rawTextBuffer.toString()
        currentFgColor = null
        currentBgColor = null
        val processedText = runCatching {
            if (renderAnsi) parseAnsi(fullText) else fullText
        }.getOrElse {
            fullText
        }
        
        text = processedText

        if (hasSelection) {
            val len = processedText.length
            val newStart = (selStart - renderedCharsDropped).coerceIn(0, len)
            val newEnd = (selEnd - renderedCharsDropped).coerceIn(0, len)
            if (newStart != newEnd) {
                val spannable = text as? Spannable
                if (spannable != null) {
                    Selection.setSelection(spannable, newStart, newEnd)
                }
            }
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

    private fun trimBuffer(): Int {
        var cutIndex = 0
        if (rawTextBuffer.length > MAX_CHARS) {
            cutIndex = rawTextBuffer.length - MAX_CHARS
        }

        var newlineCount = 0
        for (i in 0 until rawTextBuffer.length) {
            if (rawTextBuffer[i] == '\n') {
                newlineCount++
            }
        }

        if (newlineCount > MAX_LINES) {
            var linesToDrop = newlineCount - MAX_LINES
            var i = 0
            while (linesToDrop > 0 && i < rawTextBuffer.length) {
                if (rawTextBuffer[i] == '\n') {
                    linesToDrop--
                }
                i++
            }
            cutIndex = maxOf(cutIndex, i)
        }

        if (cutIndex > 0) {
            val droppedPrefix = rawTextBuffer.substring(0, cutIndex)
            val renderedDroppedLen = if (renderAnsi) parseAnsi(droppedPrefix).length else droppedPrefix.length
            rawTextBuffer.delete(0, cutIndex)
            return renderedDroppedLen
        }
        return 0
    }

    /**
     * Filters OSC sequences (ESC ] ... BEL/ST — e.g. xterm window titles) out of the
     * stream before buffering. CSI sequences pass through untouched; parseAnsi strips
     * or renders them later.
     */
    private fun processChar(ch: Char) {
        when (oscState) {
            OscState.ESC_SEEN -> {
                oscState = OscState.NONE
                if (ch == ']') {
                    oscState = OscState.IN_OSC
                    oscLength = 0
                    return
                }
                // Not an OSC — emit the withheld ESC, then handle ch normally.
                appendChar('\u001B')
            }
            OscState.IN_OSC -> {
                oscLength++
                when {
                    ch == '\u0007' -> oscState = OscState.NONE
                    ch == '\u001B' -> oscState = OscState.IN_OSC_ESC_SEEN
                    oscLength > MAX_OSC_LENGTH -> oscState = OscState.NONE
                }
                return
            }
            OscState.IN_OSC_ESC_SEEN -> {
                oscState = if (ch == '\\') OscState.NONE else OscState.IN_OSC
                return
            }
            OscState.NONE -> Unit
        }
        if (ch == '\u001B') {
            oscState = OscState.ESC_SEEN
            return
        }
        appendChar(ch)
    }

    /**
     * Appends with carriage-return overwrite semantics: a `\r` not followed by `\n`
     * restarts the current line, so shell prompt redraws (SIGWINCH) and progress bars
     * (wget, apt) repaint one line instead of appending duplicates.
     */
    private fun appendChar(ch: Char) {
        when (ch) {
            '\r' -> pendingCarriageReturn = true
            '\n' -> {
                rawTextBuffer.append('\n')
                pendingCarriageReturn = false
            }
            '\b' -> {
                // Remote echoes "\b \b" to erase a character; apply the erase locally.
                eraseLastPrintableChar()
            }
            else -> {
                if (pendingCarriageReturn) {
                    val lastNewline = rawTextBuffer.lastIndexOf("\n")
                    rawTextBuffer.setLength(if (lastNewline >= 0) lastNewline + 1 else 0)
                    pendingCarriageReturn = false
                }
                rawTextBuffer.append(ch)
            }
        }
    }

    /**
     * Erases the last printable character on the current line, never crossing a `\n`.
     * Trailing CSI/SGR escape sequences (`ESC [ [0-9;?]* letter`, e.g. a `ESC[0m`
     * color reset) are skipped rather than truncated, so a backspace can't corrupt a
     * still-open escape sequence into raw codes. OSC is already stripped upstream in
     * [processChar], so only CSI sequences and printable text reach the buffer.
     */
    private fun eraseLastPrintableChar() {
        var i = rawTextBuffer.length - 1
        while (i >= 0) {
            val c = rawTextBuffer[i]
            if (c == '\n') return
            if (c.isLetter()) {
                // Possible CSI/SGR terminator — walk back over its parameter bytes.
                var j = i - 1
                while (j >= 0 && (rawTextBuffer[j].isDigit() || rawTextBuffer[j] == ';' || rawTextBuffer[j] == '?')) {
                    j--
                }
                if (j >= 1 && rawTextBuffer[j] == '[' && rawTextBuffer[j - 1] == '\u001B') {
                    i = j - 2
                    continue
                }
            }
            rawTextBuffer.deleteCharAt(i)
            return
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
                    in 30..37 -> currentFgColor = ansiColor(code - 30)
                    39 -> currentFgColor = null
                    in 40..47 -> currentBgColor = ansiColor(code - 40)
                    49 -> currentBgColor = null
                    in 90..97 -> currentFgColor = ansiColor(code - 90 + BRIGHT_OFFSET)
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
