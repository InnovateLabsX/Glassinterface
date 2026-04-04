package com.glassinterface.core.voice

/**
 * Parses raw speech-to-text output into structured [VoiceCommand] intents.
 *
 * Uses keyword matching — fast and works fully offline.
 * The parser is intentionally forgiving: "save my face" and "remember face" both match.
 *
 * v0.6.2: Unrecognized speech is routed to [CommandType.ASK_GEMINI] instead of [CommandType.UNKNOWN]
 * so the Gemini LLM can attempt to answer any free-form question.
 */
object VoiceCommandParser {

    fun parse(text: String): VoiceCommand {
        val lower = text.lowercase().trim()

        return when {
            // ── Stop / Silence (highest priority) ──
            lower.containsAny("stop", "be quiet", "shut up", "silence", "mute", "enough") ->
                VoiceCommand(CommandType.STOP)

            // ── Repeat ──
            lower.containsAny("repeat", "say again", "say that again", "what did you say", "repeat that") ->
                VoiceCommand(CommandType.REPEAT)

            // ── Face commands ──
            lower.containsAny("save face", "save my face", "remember this person", "remember face", "save this face") ->
                VoiceCommand(CommandType.SAVE_FACE, extractAfter(lower, "as", "named"))

            lower.containsAny("who is this", "who is that", "identify", "recognize") ->
                VoiceCommand(CommandType.IDENTIFY_FACE)

            // ── Object commands ──
            lower.containsAny("save this", "save object", "remember this object", "remember this", "save what i see") ->
                VoiceCommand(CommandType.SAVE_OBJECT)

            // ── Contact commands ──
            lower.containsAny("save contact", "add contact", "remember contact") ->
                VoiceCommand(CommandType.SAVE_CONTACT, extractAfter(lower, "contact", "named", "called"))

            // ── Location commands ──
            lower.containsAny("save location", "remember this place", "save this place", "mark location", "save where i am") ->
                VoiceCommand(CommandType.SAVE_LOCATION, extractAfter(lower, "as", "called"))

            // ── Navigate ──
            lower.containsAny("navigate to", "take me to", "directions to", "how do i get to", "go to") ->
                VoiceCommand(CommandType.NAVIGATE, extractAfter(lower, "to"))

            // ── Timestamp commands ──
            lower.containsAny("save time", "mark time", "save timestamp", "mark timestamp", "remember time") ->
                VoiceCommand(CommandType.SAVE_TIMESTAMP, extractAfter(lower, "as", "note"))

            // ── Note commands ──
            lower.containsAny("save note", "take note", "note that", "remember that") ->
                VoiceCommand(CommandType.SAVE_NOTE, extractAfter(lower, "note", "that"))

            // ── Describe scene ──
            lower.containsAny("what do you see", "describe", "what's around", "what is around",
                "tell me what you see", "scene", "look around", "what's in front") ->
                VoiceCommand(CommandType.DESCRIBE_SCENE)

            // ── Read text / OCR ──
            lower.containsAny("read this", "read that", "read the sign", "read text", "what does it say",
                "what does that say", "read the label") ->
                VoiceCommand(CommandType.READ_TEXT)

            // ── Time & Date ──
            lower.containsAny("what time", "what's the time", "tell me the time",
                "what date", "what's the date", "what day is it", "what's today") ->
                VoiceCommand(CommandType.TIME_DATE)

            // ── Battery ──
            lower.containsAny("battery", "battery level", "how much battery", "charge level", "power level") ->
                VoiceCommand(CommandType.BATTERY)

            // ── List memories ──
            lower.containsAny("list memories", "what did i save", "my memories", "what do i have",
                "list saved", "show memories") ->
                VoiceCommand(CommandType.LIST_MEMORIES)

            // ── Help ──
            lower.containsAny("help", "what can you do", "commands", "options") ->
                VoiceCommand(CommandType.HELP)

            // ── Explicit Gemini triggers ──
            lower.containsAny("ask gemini", "hey gemini", "ask ai", "ask assistant") ->
                VoiceCommand(CommandType.ASK_GEMINI, extractAfter(lower, "gemini", "ai", "assistant"))

            // ── Safety / Q&A questions (route to Gemini) ──
            lower.containsAny("is it safe", "should i", "can i", "how do i",
                "what is", "what are", "tell me about", "explain") ->
                VoiceCommand(CommandType.ASK_GEMINI, lower)

            // ── Fallback: anything unrecognized → Gemini Q&A ──
            else -> VoiceCommand(CommandType.ASK_GEMINI, lower)
        }
    }

    /**
     * Extract the payload text after any of the given keywords.
     * e.g. "save face as John" → "John"
     */
    private fun extractAfter(text: String, vararg keywords: String): String {
        for (keyword in keywords) {
            val idx = text.indexOf(keyword)
            if (idx >= 0) {
                val after = text.substring(idx + keyword.length).trim()
                if (after.isNotBlank()) return after
            }
        }
        return ""
    }

    private fun String.containsAny(vararg phrases: String): Boolean =
        phrases.any { this.contains(it) }
}
