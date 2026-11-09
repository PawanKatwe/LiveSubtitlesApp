package com.translator.livesubtitles.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * Ultra-fast Hybrid Cloud Translation using Groq or OpenAI Whisper.
 * Sends audio to /audio/translations endpoint which directly translates any source language to English.
 * Groq Whisper has ~150-250ms inference time, providing near zero-latency live subtitles.
 */
class CloudWhisperEngine(
    private val apiKey: String,
    private val endpointUrl: String = "https://api.groq.com/openai/v1/audio/translations",
    private val modelName: String = "whisper-large-v3"
) : TranslationEngine {

    companion object {
        private const val TAG = "CloudWhisperEngine"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun translateAudioChunk(pcmData: ShortArray): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext "[Error: API key missing]"
        }

        try {
            val wavBytes = createWavByteArray(pcmData, 16000, 1)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    wavBytes.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", modelName)
                .addFormDataPart("response_format", "text")
                .build()

            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "HTTP ${response.code}"
                    Log.e(TAG, "API call failed: $err")
                    return@withContext null
                }

                val translatedText = response.body?.string()?.trim()
                if (!translatedText.isNullOrEmpty()) {
                    translatedText
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Translation network error", e)
            null
        }
    }

    /**
     * Converts raw 16-bit PCM samples into standard 44-byte WAV format in memory.
     */
    private fun createWavByteArray(pcmData: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val byteBuffer = ByteArrayOutputStream()
        val out = DataOutputStream(byteBuffer)

        val totalAudioLen = pcmData.size * 2
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * 2

        // RIFF header
        out.writeBytes("RIFF")
        writeIntLE(out, totalDataLen)
        out.writeBytes("WAVE")

        // fmt chunk
        out.writeBytes("fmt ")
        writeIntLE(out, 16) // sub-chunk size
        writeShortLE(out, 1.toShort()) // AudioFormat (1 = PCM)
        writeShortLE(out, channels.toShort())
        writeIntLE(out, sampleRate)
        writeIntLE(out, byteRate)
        writeShortLE(out, (channels * 2).toShort()) // block align
        writeShortLE(out, 16.toShort()) // bits per sample

        // data chunk
        out.writeBytes("data")
        writeIntLE(out, totalAudioLen)

        // Write PCM 16-bit little endian
        for (sample in pcmData) {
            out.write(sample.toInt() and 0xFF)
            out.write((sample.toInt() shr 8) and 0xFF)
        }

        out.flush()
        return byteBuffer.toByteArray()
    }

    private fun writeIntLE(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xFF)
        out.writeByte((value shr 8) and 0xFF)
        out.writeByte((value shr 16) and 0xFF)
        out.writeByte((value shr 24) and 0xFF)
    }

    private fun writeShortLE(out: DataOutputStream, value: Short) {
        out.writeByte(value.toInt() and 0xFF)
        out.writeByte((value.toInt() shr 8) and 0xFF)
    }

    override fun release() {
        httpClient.dispatcher.executorService.shutdown()
    }
}
