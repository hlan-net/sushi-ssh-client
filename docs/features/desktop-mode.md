# Desktop Mode / External Display Support

## Problem

Android 16+ desktop windowing turns a phone connected to an external display into a
desktop-like workstation: apps run in freeform windows, a hardware keyboard and mouse
are the primary input, and users multitask across windows. A phone + monitor + keyboard
is a natural SSH workstation — arguably the best form factor Sushi could run on — but
the current UX does not take advantage of it.

## Current state (assessed 2026-07, v0.7.3)

What already works:

- targetSdk 36: the app is resizable by default on displays ≥ 600dp; no
  `screenOrientation` locks anywhere, so no compatibility letterboxing
- `TerminalActivity` declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`
  and resizes the PTY on window size changes (`onSizeChangedListener` → `resizePty`),
  so the remote shell reflows correctly when the window is resized
- Carriage-return overwrite handling (v0.7.4) keeps SIGWINCH prompt redraws clean
  during resizes

What is missing:

- **Hardware keyboard support in the terminal is minimal.** `TerminalView`'s
  `sendKeyEvent` handles only Enter, Tab, and Del. No Ctrl+letter combos (Ctrl-C is a
  touch button only, `TerminalActivity`), no Esc, no arrow keys, no Home/End/PgUp/PgDn,
  no function keys. On a desktop this makes interactive shell work impractical.
- **Every other activity recreates on window resize.** Only `TerminalActivity` has
  `configChanges`; `MainActivity` (hosting the Gemini dialog) is destroyed and rebuilt
  on every freeform window drag, dismissing the open conversation dialog.
- **No adaptive layouts.** `res/layout/` is the only layout directory — a maximized
  window on a 27" display renders the same single-column phone UI.
- Desktop windows can be as small as 386 × 352 dp, smaller than any phone; layouts have
  never been exercised at that size.

## Use cases

- Phone + USB-C dock at a desk: full SSH workstation without a laptop
- Server maintenance from a hotel/meeting room TV with a Bluetooth keyboard
- The same keyboard improvements benefit phone users with Bluetooth keyboards today

## Implementation ideas (priority order)

1. **Physical keyboard support in `TerminalView`** — map hardware `KeyEvent`s to
   terminal bytes: Ctrl+A–Z → 0x01–0x1A, Esc → 0x1B, arrows → `ESC [ A/B/C/D`,
   Home/End, PgUp/PgDn, Delete. Overlaps with the extra-keys row proposed in
   [improvements/02-terminal-emulation.md](../improvements/02-terminal-emulation.md);
   the key-to-byte mapping table should be shared between the on-screen row and the
   hardware key handler.
2. **Window-resize resilience outside the terminal** — add `configChanges` handling or
   proper state preservation to `MainActivity` so the Gemini conversation survives
   freeform window drags. (A dialog-hosted conversation is inherently fragile; the
   improvements/04 page-controller refactor is the natural moment to fix this.)
3. **Adaptive layout for wide windows** — at expanded width (≥ 840dp), show the
   terminal and the Gemini transcript side by side instead of a dialog overlay.
4. **Test procedure** — Pixel with USB-C DisplayPort output against a monitor, or the
   Android emulator's desktop AVD profile. Add a `LayoutInflationTest`-style check at
   the 386 × 352 dp minimum window size.

## Notes

- Related: [improvements/02-terminal-emulation.md](../improvements/02-terminal-emulation.md)
  (extra keys, emulator core), [improvements/06-ux.md](../improvements/06-ux.md)
  (font size, readability)
- Reference: [Android desktop windowing guide](https://developer.android.com/develop/adaptive-apps/guides/support-desktop-windowing),
  [app resizability on Android 16+](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)
- No code changes committed yet — this document is the assessment and backlog.
