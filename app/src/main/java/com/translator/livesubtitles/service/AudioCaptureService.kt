package com.translator.livesubtitles.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.translator.livesubtitles.MainActivity
import com.translator.livesubtitles.R
import com.translator.livesubtitles.audio.AudioProcessor
import com.translator.livesubtitles.engine.CloudWhisperEngine
import com.translator.livesubtitles.engine.OfflineWhisperEngine
import com.translator.livesubtitles.engine.TranslationEngine
import com.translator.livesubtitles.overlay.OverlaySubtitleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "live_subtitles_channel"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ENGINE_TYPE = "extra_engine_type"
        const val EXTRA_API_KEY = "extra_api_key"

        const val ENGINE_OFFLINE = 1
        const val ENGINE_HYBRID_CLOUD = 2

        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var overlayManager: OverlaySubtitleManager? = null
    private var translationEngine: TranslationEngine? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var recordingJob: Job? = null

    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Crucial: Always promote to foreground immediately to satisfy Android 14 5-second SLA
        startForegroundServiceWithNotification()

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        val engineType = intent.getIntExtra(EXTRA_ENGINE_TYPE, ENGINE_OFFLINE)
        val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""

        if (resultCode != -1 && resultData != null) {
            try {
                initOverlay()
                initEngine(engineType, apiKey)
                startAudioPlaybackCapture(resultCode, resultData)
                isRunning = true
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error starting audio capture", e)
                overlayManager?.updateSubtitle("Startup error: ${e.message}")
            }
        } else {
            Log.e(TAG, "Missing MediaProjection resultCode or resultData")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_running_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Live Subtitles Active")
            .setContentText("Capturing internal system audio and translating to English")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initEngine(engineType: Int, apiKey: String) {
        try {
            translationEngine = when (engineType) {
                ENGINE_HYBRID_CLOUD -> CloudWhisperEngine(apiKey = apiKey)
                else -> OfflineWhisperEngine(this)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error initializing translation engine", e)
            overlayManager?.updateSubtitle("Engine init failed: ${e.message}")
        }
    }

    private fun initOverlay() {
        overlayManager = OverlaySubtitleManager(this) {
            stopSelf()
        }
        overlayManager?.show()
    }

    private fun startAudioPlaybackCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection token is null")
            overlayManager?.updateSubtitle("Error: MediaProjection permission not granted")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Android 14 requires callback registration before capture
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection stopped by system")
                        stopSelf()
                    }
                }, null)

                // Capture media, games, and unknown usages
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                audioRecord?.startRecording()
                overlayManager?.updateStatus("🟢 Listening to System Audio")
                startCaptureLoop()

            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in AudioPlaybackCapture", e)
                overlayManager?.updateSubtitle("Audio permission denied. Check app permissions.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioRecord with playback capture", e)
                overlayManager?.updateSubtitle("AudioRecord error: ${e.message}")
            }
        }
    }

    private fun startCaptureLoop() {
        val processor = AudioProcessor(
            sampleRate = sampleRate,
            onStatusUpdate = { status ->
                overlayManager?.updateStatus(status)
            }
        ) { pcmChunk ->
            overlayManager?.updateStatus("🟡 Translating dialogue...")
            val englishTranslation = translationEngine?.translateAudioChunk(pcmChunk)
            if (!englishTranslation.isNullOrBlank()) {
                overlayManager?.updateSubtitle(englishTranslation)
            }
        }

        recordingJob = serviceScope.launch {
            val audioBuffer = ShortArray(bufferSize / 2)
            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val readCount = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readCount > 0) {
                    processor.processSamples(audioBuffer, readCount)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        recordingJob?.cancel()
        serviceScope.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audioRecord", e)
        }

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mediaProjection", e)
        }

        translationEngine?.release()
        translationEngine = null

        overlayManager?.hide()
        overlayManager = null

        Log.i(TAG, "AudioCaptureService destroyed cleanly.")
    }
}
