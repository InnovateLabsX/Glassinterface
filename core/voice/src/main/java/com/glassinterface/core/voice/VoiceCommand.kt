package com.glassinterface.core.voice

/**
 * Parsed voice command intent with optional payload extracted from speech.
 */
data class VoiceCommand(
    val type: CommandType,
    val payload: String = ""
)

/**
 * All supported voice command intents.
 */
enum class CommandType {
    /** "save face" / "remember this person" — crop + save face */
    SAVE_FACE,
    /** "save this" / "remember this object" — crop + save object */
    SAVE_OBJECT,
    /** "save contact [name]" — store a contact */
    SAVE_CONTACT,
    /** "save location" / "remember this place" — save GPS */
    SAVE_LOCATION,
    /** "save time" / "mark timestamp" — save current time */
    SAVE_TIMESTAMP,
    /** "note [text]" / "save note [text]" — free-form note */
    SAVE_NOTE,
    /** "what do you see?" / "describe" — read scene aloud */
    DESCRIBE_SCENE,
    /** "who is this?" — identify the face in frame */
    IDENTIFY_FACE,
    /** "what did I save?" / "list memories" — summary of saves */
    LIST_MEMORIES,
    /** "help" — list available commands */
    HELP,

    // ── v0.6.2 New Commands ──

    /** Free-form question routed to Gemini LLM */
    ASK_GEMINI,
    /** "read this" / "read that sign" — OCR placeholder */
    READ_TEXT,
    /** "navigate to [place]" — directions request */
    NAVIGATE,
    /** "what time is it" / "what's the date" — time/date */
    TIME_DATE,
    /** "battery level" — device battery readout */
    BATTERY,
    /** "repeat that" / "say again" — replay last response */
    REPEAT,
    /** "stop" / "be quiet" — silence TTS immediately */
    STOP,

    /** Unrecognised speech — fallback to Gemini if API key available */
    UNKNOWN
}
