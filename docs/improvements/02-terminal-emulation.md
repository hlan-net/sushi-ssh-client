# Terminal emulation improvements

## Where we are

`TerminalView` is an `AppCompatTextView` that appends text and post-processes
ANSI SGR color codes with regexes (TerminalView.kt:253–315). Everything that is
not a color code is stripped:

```kotlin
// Strip out non-color ANSI escape sequences (e.g. cursor movements)
private val ESCAPE_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[a-ln-zA-LN-Z]")
```

The PTY is requested as `xterm` (SshClient.kt:223), so remote programs *believe*
they can address the cursor, clear regions, and use the alternate screen — but
the view renders none of it.

## 1. Interactive programs don't work (P1)

**Finding.** Anything that uses cursor addressing — `vim`, `nano`, `htop`,
`less`, `top`, `tmux`, interactive `docker`/`kubectl` — renders as garbage or
a frozen wall of text. For an SSH client this is the single biggest functional
gap; it is also a prerequisite for the roadmap's "Raw Terminal Mode" being
genuinely useful.

**Proposal.** Two viable paths, in order of preference:

1. **Adopt a proven emulator core.** The Termux `terminal-emulator` +
   `terminal-view` libraries (Apache-2.0/GPLv3 — verify license fit; jackpal's
   `Android-Terminal-Emulator` is Apache-2.0) implement a real screen buffer,
   alternate screen, scroll regions, 256-color/truecolor, and mouse reporting.
   `TerminalBackend` already abstracts the transport, so the emulator only
   replaces the *view* layer.
2. **Grow our own screen-buffer emulator.** A `TerminalBuffer` (grid of cells:
   char + fg + bg + attrs) plus a parser for the CSI subset (CUP, ED, EL, SGR,
   DECSET 1049 alt-screen) rendered on a custom `View` with `Canvas.drawText`
   per line. More work (~2–3 weeks) but no external dependency and full control
   over selection/a11y behaviour.

Keep the current `TerminalView` as the renderer for the *conversation log* use
case — it is fine for append-only output — and use the real emulator for the
interactive terminal screen.

**Effort.** Path 1: ~1 week incl. theming and IME glue. Path 2: ~2–3 weeks.

## 2. Missing keys make even the plain shell painful (P0)

**Finding.** The input connection handles only Enter, Tab and Backspace
(TerminalView.kt:95–106); `TerminalActivity` adds Ctrl-C / Ctrl-D buttons.
There is:

- no **Esc** (vi users are stranded),
- no **arrow keys** → no shell history (`↑`), no line editing, no menu-driven
  installers,
- no generic **Ctrl+letter** modifier (Ctrl-R history search, Ctrl-Z, Ctrl-L),
- no **Home/End/PgUp/PgDn/F-keys**,
- physical/Bluetooth keyboards: `sendKeyEvent` swallows everything except the
  three handled keys, so hardware arrow keys are dropped too.

**Proposal.**

1. Add an **extra-keys row** above the IME (Esc, Tab, Ctrl, Alt, ←↓↑→, |, /, -)
   sending the proper escape sequences (`ESC[A`–`ESC[D` for arrows,
   `ESC` for Esc; Ctrl as a sticky modifier that ANDs the next key with 0x1F).
2. Handle `KeyEvent` key codes for DPAD/arrow, Escape, Ctrl-modified letters,
   Home/End/PageUp/PageDown and function keys in `sendKeyEvent` for hardware
   keyboards.
3. Send application-cursor-mode variants when the remote enables DECCKM (needs
   item 1 of the emulation work; until then plain `ESC[A`… is fine).

**Effort.** Extra-keys row + key handling ~2–3 days, independent of the
emulator core — ship it first.

## 3. Rendering performance degrades with session length (P1)

**Finding.** Every `appendLog()` call:

- re-parses the **entire** buffer through both regexes and rebuilds a fresh
  `SpannableStringBuilder` (`updateText` → `parseAnsi(fullText)`), and
- `trimBuffer()` counts newlines over the whole buffer,

so cost per append grows linearly with buffer size → O(n²) over a session, on
the UI thread. With `MAX_CHARS = 200_000` a busy `tail -f` will occupy the main
thread with repeated 200 kB regex passes and full re-layouts, and each
`text = processedText` also discards and recreates the layout.

**Proposal (if the TextView renderer is kept for logs).**

1. Parse **incrementally**: keep the parsed `SpannableStringBuilder` as state,
   append only the new chunk (SGR state is already tracked in
   `currentFgColor/currentBgColor`), and use `TextView` with
   `setText(spannable, BufferType.SPANNABLE)` + `append()`.
2. Trim by deleting from the head of the same spannable instead of re-parsing.
3. Batch appends with a ~16 ms coalescing window (the reader thread can produce
   dozens of chunks per frame).
4. Track the newline count as a running counter instead of rescanning.

A real emulator core (item 1) makes this moot for the interactive screen, but
the conversation log view still benefits.

**Effort.** ~2 days plus regression tests around trimming/selection.

## 4. Correctness details worth fixing opportunistically

- `normalizeLineEndings` sets `pendingCarriageReturn` but never acts on it —
  `\r` is silently dropped, so progress bars (`wget`, `apt`) stack up instead of
  overwriting one line. Minimal fix: on `\r` without `\n`, erase back to the
  last line start in the buffer.
- `onSizeChanged` measures `"W"` only; fine for monospace ASCII but wide (CJK)
  glyphs will overflow the reported column count. Note as a known limitation.
- `MAX_LINES = 500` scrollback is small for server work; make it a setting
  (500/2000/10000) once trimming is O(1).
