package com.translator.livesubtitles.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-Device Offline Speech-to-English translation using Whisper via sherpa-onnx.
 * Whisper supports automatic translation to English using `task = "translate"`.
 */
class OfflineWhisperEngine(private val context: Context) : TranslationEngine {

    companion object {
        private const val TAG = "OfflineWhisperEngine"
    }

    private var recognizer: OfflineRecognizer? = null
    private var isInitialized = false

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        try {
            // Models can be bundled in app internal files or assets
            // Example structure: filesDir/models/whisper-tiny/
            val modelDir = File(context.filesDir, "models/whisper-tiny")
            val encoder = File(modelDir, "tiny-encoder.int8.onnx")
            val decoder = File(modelDir, "tiny-decoder.int8.onnx")
            val tokens = File(modelDir, "tiny-tokens.txt")

            if (encoder.exists() && decoder.exists() && tokens.exists()) {
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = "",       // Auto-detect source language
                    task = "translate",   // Translate to English!
                    tailPaddings = 1000
                )

                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )

                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = modelConfig
                )

                recognizer = OfflineRecognizer(context.assets, config)
                isInitialized = true
                Log.i(TAG, "Offline Whisper model loaded successfully.")
            } else {
                Log.w(TAG, "Whisper model files not found in ${modelDir.absolutePath}. Expecting tiny-encoder.int8.onnx, etc.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OfflineRecognizer", e)
        }
    }

    override suspend fun translateAudioChunk(pcmData: ShortArray): String? = withContext(Dispatchers.Default) {
        if (!isInitialized || recognizer == null) {
            return@withContext "[Offline model not downloaded. Tap 'Download Model' in app or switch to Hybrid mode]"
        }

        try {
            // Convert Short PCM to Normalized Float samples (-1.0 to 1.0)
            val floatSamples = FloatArray(pcmData.size) { pcmData[it] / 32768.0f }

            val stream = recognizer!!.createStream()
            stream.acceptWaveform(floatSamples, 16000)
            recognizer!!.decode(stream)
            val result = recognizer!!.getResult(stream)
            val text = result.text.trim()
            stream.release()

            if (text.isNotEmpty()) text else null
        } catch (e: Exception) {
            Log.e(TAG, "Error translating audio chunk", e)
            null
        }
    }

    override fun release() {
        try {
            recognizer?.release()
            recognizer = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recognizer", e)
        }
    }
}
