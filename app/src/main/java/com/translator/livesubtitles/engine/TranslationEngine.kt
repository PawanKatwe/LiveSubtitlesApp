package com.translator.livesubtitles.engine

/**
 * Common interface for translation engines.
 * Takes raw 16-bit PCM audio samples (16kHz mono) and returns English translation.
 */
interface TranslationEngine {
    /**
     * Translates the provided audio chunk into English text.
     * @param pcmData 16-bit PCM mono samples at 16kHz
     * @return English translated subtitle string, or null/empty if no speech detected
     */
    suspend fun translateAudioChunk(pcmData: ShortArray): String?

    /**
     * Clean up models or network resources.
     */
    fun release()
}
