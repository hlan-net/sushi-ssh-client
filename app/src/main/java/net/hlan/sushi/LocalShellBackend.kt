package net.hlan.sushi

import android.content.Context
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

class LocalShellBackend(private val context: Context) : TerminalBackend {
    private var nativeHandle: Long = 0L
    private var readerThread: Thread? = null

    override fun connect(
        onLine: (String) -> Unit,
        streamMode: Boolean,
        onConnectionClosed: (() -> Unit)?,
    ): SshConnectResult {
        val shell = System.getenv("SHELL").takeUnless { it.isNullOrBlank() }
            ?: "/system/bin/sh"
        val handle = nativeStart(shell, arrayOf(shell, "-i"), defaultEnv())
        if (handle == 0L) {
            return SshConnectResult(false, "PTY allocation failed")
        }
        nativeHandle = handle
        startReader(onLine, streamMode, onConnectionClosed)
        return SshConnectResult(true, "Connected to local shell")
    }

    override fun isConnected(): Boolean = nativeHandle != 0L

    override fun sendText(text: String): SshCommandResult {
        val h = nativeHandle
        if (h == 0L) return SshCommandResult(false, null, "Not connected")
        if (text.isEmpty()) return SshCommandResult(true, null, "No input")
        return runCatching {
            val n = nativeWrite(h, text.toByteArray(Charsets.UTF_8))
            if (n < 0) SshCommandResult(false, null, "Write failed")
            else SshCommandResult(true, null, "Input sent")
        }.getOrElse { e ->
            SshCommandResult(false, null, e.message ?: "Write error")
        }
    }

    override fun sendCommand(command: String): SshCommandResult {
        val payload = if (command.endsWith("\n")) command else "$command\n"
        return sendText(payload)
    }

    override fun sendCtrlC() {
        val h = nativeHandle
        if (h != 0L) runCatching { nativeWrite(h, byteArrayOf(3)) }
    }

    override fun sendCtrlD() {
        val h = nativeHandle
        if (h != 0L) runCatching { nativeWrite(h, byteArrayOf(4)) }
    }

    override fun resizePty(col: Int, row: Int, widthPx: Int, heightPx: Int) {
        val h = nativeHandle
        if (h != 0L) runCatching { nativeResize(h, col, row, widthPx, heightPx) }
    }

    override fun disconnect() {
        val h = nativeHandle
        nativeHandle = 0L
        if (h != 0L) runCatching { nativeClose(h) }
        readerThread = null
    }

    private fun startReader(
        onLine: (String) -> Unit,
        streamMode: Boolean,
        onConnectionClosed: (() -> Unit)?,
    ) {
        val h = nativeHandle
        readerThread = Thread {
            val buf = ByteArray(4096)
            // CharsetDecoder accumulates bytes across read() calls so that
            // multi-byte UTF-8 sequences split at a read boundary are decoded
            // correctly instead of producing replacement characters.
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val charBuf = CharBuffer.allocate(8192)
            val lineBuffer = StringBuilder()
            var pending = ByteArray(0)
            try {
                while (true) {
                    val n = nativeRead(h, buf)
                    if (n <= 0) break
                    pending = decodeIntoBuffer(pending, buf, n, decoder, charBuf)
                    if (!charBuf.hasRemaining()) continue
                    dispatchOutput(charBuf.toString(), streamMode, lineBuffer, onLine)
                }
                flushDecoder(pending, decoder, charBuf, streamMode, lineBuffer, onLine)
            } finally {
                onConnectionClosed?.invoke()
            }
        }.apply {
            isDaemon = true
            name = "LocalShellReader"
            start()
        }
    }

    private fun decodeIntoBuffer(
        pending: ByteArray,
        buf: ByteArray,
        n: Int,
        decoder: CharsetDecoder,
        charBuf: CharBuffer,
    ): ByteArray {
        val input = if (pending.isEmpty()) buf.copyOf(n) else pending + buf.copyOf(n)
        val inBuf = ByteBuffer.wrap(input)
        charBuf.clear()
        decoder.decode(inBuf, charBuf, false)
        charBuf.flip()
        return if (inBuf.hasRemaining()) ByteArray(inBuf.remaining()).also { inBuf.get(it) } else ByteArray(0)
    }

    private fun dispatchOutput(
        chunk: String,
        streamMode: Boolean,
        lineBuffer: StringBuilder,
        onLine: (String) -> Unit,
    ) {
        if (streamMode) {
            onLine(chunk)
        } else {
            lineBuffer.append(chunk)
            val text = lineBuffer.toString()
            val lastNl = text.lastIndexOf('\n')
            if (lastNl >= 0) {
                onLine(text.substring(0, lastNl + 1))
                lineBuffer.setLength(0)
                lineBuffer.append(text.substring(lastNl + 1))
            }
        }
    }

    private fun flushDecoder(
        pending: ByteArray,
        decoder: CharsetDecoder,
        charBuf: CharBuffer,
        streamMode: Boolean,
        lineBuffer: StringBuilder,
        onLine: (String) -> Unit,
    ) {
        charBuf.clear()
        decoder.decode(ByteBuffer.wrap(pending), charBuf, true)
        decoder.flush(charBuf)
        charBuf.flip()
        if (charBuf.hasRemaining()) {
            val tail = charBuf.toString()
            if (streamMode) onLine(tail) else lineBuffer.append(tail)
        }
        if (!streamMode && lineBuffer.isNotEmpty()) {
            onLine(lineBuffer.toString())
        }
    }

    private fun defaultEnv(): Array<String> = listOf(
        "HOME=${System.getenv("HOME") ?: context.filesDir.absolutePath}",
        "PATH=${System.getenv("PATH") ?: "/system/bin:/system/xbin"}",
        "ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: "/data"}",
        "ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: "/system"}",
        "TMPDIR=${context.cacheDir.absolutePath}",
        "TERM=xterm-256color",
    ).toTypedArray()

    companion object {
        init {
            System.loadLibrary("sushi-pty")
        }

        @JvmStatic external fun nativeStart(cmd: String, argv: Array<String>, envp: Array<String>): Long
        @JvmStatic external fun nativeRead(handle: Long, buf: ByteArray): Int
        @JvmStatic external fun nativeWrite(handle: Long, data: ByteArray): Int
        @JvmStatic external fun nativeResize(handle: Long, col: Int, row: Int, widthPx: Int, heightPx: Int)
        @JvmStatic external fun nativeClose(handle: Long)
    }
}
