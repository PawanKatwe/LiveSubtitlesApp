package com.translator.livesubtitles.audio

import kotlin.math.sqrt

/**
 * Processes continuous audio samples from system playback capture.
 * Detects voice activity using energy (RMS) thresholding and packages audio into
 * optimal slices (1.5 to 3.5 seconds) for translation models.
 */
class AudioProcessor(
    private val sampleRate: Int = 16000,
    private val energyThreshold: Double = 300.0, // Sensitive threshold for media dialogue
    private val onStatusUpdate: ((String) -> Unit)? = null,
    private val onChunkReady: suspend (ShortArray) -> Unit
) {

    // Buffer limits (in samples)
    private val minChunkSamples = (sampleRate * 1.5).toInt()  // 1.5 seconds min
    private val maxChunkSamples = (sampleRate * 3.5).toInt()  // 3.5 seconds max
    private val silenceThresholdSamples = (sampleRate * 0.5).toInt() // 500ms pause triggers segment

    private val speechBuffer = ArrayList<Short>()
    private var consecutiveSilenceSamples = 0
    private var hasSpeech = false

    private var sampleCounter = 0
    private var lastStatusUpdate = 0L

    suspend fun processSamples(samples: ShortArray, readCount: Int) {
        if (readCount <= 0) return

        // Calculate Root Mean Square (RMS) energy for this frame
        var sumSquares = 0.0
        for (i in 0 until readCount) {
            sumSquares += samples[i].toDouble() * samples[i].toDouble()
        }
        val rms = sqrt(sumSquares / readCount)
        val isSpeech = rms > energyThreshold

        sampleCounter += readCount
        val now = System.currentTimeMillis()
        if (now - lastStatusUpdate > 800) {
            lastStatusUpdate = now
            if (isSpeech) {
                onStatusUpdate?.invoke("🟢 Voice Detected")
            } else if (rms > 80) {
                onStatusUpdate?.invoke("🔉 Media Playing (Volume: ${rms.toInt()})")
            } else {
                onStatusUpdate?.invoke("⚪ Media Silent / Paused")
            }
        }

        if (isSpeech) {
            hasSpeech = true
            consecutiveSilenceSamples = 0
            for (i in 0 until readCount) {
                speechBuffer.add(samples[i])
            }
        } else {
            if (hasSpeech) {
                consecutiveSilenceSamples += readCount
                for (i in 0 until readCount) {
                    speechBuffer.add(samples[i])
                }
            }
        }

        // Trigger translation if max chunk reached or pause occurred after speech
        val shouldEmit = (speechBuffer.size >= maxChunkSamples) ||
                (hasSpeech && consecutiveSilenceSamples >= silenceThresholdSamples && speechBuffer.size >= minChunkSamples)

        if (shouldEmit) {
            emitChunk()
        }
    }

    private suspend fun emitChunk() {
        if (speechBuffer.size >= minChunkSamples) {
            val chunkArray = ShortArray(speechBuffer.size)
            for (i in 0 until speechBuffer.size) {
                chunkArray[i] = speechBuffer[i]
            }

            // Keep 250ms tail overlap to prevent clipping word edges
            val overlapSamples = (sampleRate * 0.25).toInt()
            val keepCount = if (speechBuffer.size > overlapSamples) overlapSamples else 0
            val overlapList = speechBuffer.takeLast(keepCount)

            speechBuffer.clear()
            speechBuffer.addAll(overlapList)
            consecutiveSilenceSamples = 0
            hasSpeech = false

            onChunkReady(chunkArray)
        } else {
            speechBuffer.clear()
            consecutiveSilenceSamples = 0
            hasSpeech = false
        }
    }

    fun reset() {
        speechBuffer.clear()
        consecutiveSilenceSamples = 0
        hasSpeech = false
    }
}
