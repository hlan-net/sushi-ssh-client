# Voice I/O Enhancements

## Current state

Voice input exists via the Android speech recognition API (microphone button in the Gemini dialog). There is no voice output and no always-on trigger.

---

## Wake Word

Trigger phrase ("Computer, ...") that starts voice recording without tapping the screen.

**Use cases**: Hands-free operation when the phone is mounted or the user's hands are occupied.

**Implementation**: Android `SpeechRecognizer` in continuous listening mode with keyword detection. The wake word starts a bounded recording session (same path as the existing microphone button). Custom wake words configurable in settings.

**Consideration**: Continuous microphone use has a battery and privacy cost. Should be explicitly enabled by the user and clearly indicated when active.

---

## Text-to-Speech (TTS) Responses

The system speaks AI responses instead of (or in addition to) displaying them.

**Use cases**: Hands-free monitoring, accessibility, the Star Trek computer experience.

**Implementation**: Android `TextToSpeech` API. Configurable voice, rate, and pitch. Toggle in settings (off by default). Reads the conversational response text; does not read raw command output.

**Integration**: TTS fires after each AI response is displayed, so the user can read along or just listen. A "stop speaking" button should be available.

---

## Voice-Only Mode

No screen required — the full conversational loop runs via voice in/out with no need to look at the phone.

**Target scenario**: Phone in pocket, Bluetooth headset, managing a server while doing something else.

**Requirements**: Wake word (above) + TTS (above) + audio feedback for CONFIRM prompts ("Say 'yes' to proceed or 'cancel' to abort").

**Implementation**: A dedicated mode flag that routes all I/O through audio and suppresses the transcript UI. SAFE commands auto-execute as normal; CONFIRM commands speak the prompt and listen for a voice response.

**Dependency**: Requires wake word and TTS to be solid first.
