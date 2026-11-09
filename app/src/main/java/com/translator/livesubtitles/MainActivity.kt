package com.translator.livesubtitles

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.translator.livesubtitles.databinding.ActivityMainBinding
import com.translator.livesubtitles.service.AudioCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PREFS_NAME = "live_subtitles_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ENGINE_TYPE = "engine_type"
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionUI()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startAudioService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen/Audio capture permission was not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "Audio recording permission is required to capture system sound.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        loadSavedSettings()
        setupUI()
        checkAppPermissions()
        checkOfflineModelStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
        updateServiceButtonState()
        checkOfflineModelStatus()
    }

    private fun loadSavedSettings() {
        val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        if (savedKey.isNotEmpty()) {
            binding.etApiKey.setText(savedKey)
        }

        val savedEngine = prefs.getInt(KEY_ENGINE_TYPE, AudioCaptureService.ENGINE_HYBRID_CLOUD)
        if (savedEngine == AudioCaptureService.ENGINE_OFFLINE) {
            binding.rbOffline.isChecked = true
            binding.layoutApiKey.visibility = View.GONE
            binding.layoutOfflineDownload.visibility = View.VISIBLE
        } else {
            binding.rbCloud.isChecked = true
            binding.layoutApiKey.visibility = View.VISIBLE
            binding.layoutOfflineDownload.visibility = View.GONE
        }
    }

    private fun setupUI() {
        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        binding.rgEngine.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCloud) {
                binding.layoutApiKey.visibility = View.VISIBLE
                binding.layoutOfflineDownload.visibility = View.GONE
                prefs.edit().putInt(KEY_ENGINE_TYPE, AudioCaptureService.ENGINE_HYBRID_CLOUD).apply()
            } else {
                binding.layoutApiKey.visibility = View.GONE
                binding.layoutOfflineDownload.visibility = View.VISIBLE
                prefs.edit().putInt(KEY_ENGINE_TYPE, AudioCaptureService.ENGINE_OFFLINE).apply()
                checkOfflineModelStatus()
            }
        }

        binding.btnDownloadModel.setOnClickListener {
            downloadWhisperModel()
        }

        binding.btnToggleService.setOnClickListener {
            if (AudioCaptureService.isRunning) {
                stopAudioService()
            } else {
                if (!checkOverlayPermission()) {
                    Toast.makeText(this, "Please grant Overlay permission first so subtitles can float.", Toast.LENGTH_SHORT).show()
                    binding.btnGrantOverlay.performClick()
                    return@setOnClickListener
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Audio permission needed to capture media playback.", Toast.LENGTH_SHORT).show()
                    requestAudioPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    return@setOnClickListener
                }

                // If cloud mode is selected, check API key
                if (binding.rbCloud.isChecked) {
                    val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
                    if (apiKey.isEmpty()) {
                        Toast.makeText(this, "Please enter your free Groq API key first (from console.groq.com)", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    prefs.edit().putString(KEY_API_KEY, apiKey).apply()
                }

                // If offline mode is selected, verify model is downloaded
                if (binding.rbOffline.isChecked) {
                    val modelDir = File(filesDir, "models/whisper-tiny")
                    val encoder = File(modelDir, "tiny-encoder.int8.onnx")
                    val decoder = File(modelDir, "tiny-decoder.int8.onnx")
                    val tokens = File(modelDir, "tiny-tokens.txt")
                    if (!encoder.exists() || !decoder.exists() || !tokens.exists()) {
                        Toast.makeText(this, "Please download the offline model (~75 MB) first by tapping 'Download Whisper Model'.", Toast.LENGTH_LONG).show()
                        downloadWhisperModel()
                        return@setOnClickListener
                    }
                }

                // Prompt user for MediaProjection consent (Start casting/recording)
                val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                mediaProjectionLauncher.launch(captureIntent)
            }
        }
    }

    private fun checkAppPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestAudioPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun updatePermissionUI() {
        val hasOverlay = checkOverlayPermission()
        if (hasOverlay) {
            binding.tvPermissionStatus.text = "Step 1: Overlay Permission Granted ✓"
            binding.tvPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.teal_700))
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.text = "Overlay Permission Active"
        } else {
            binding.tvPermissionStatus.text = "Step 1: Overlay Permission Required"
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.text = "Grant Overlay Permission"
        }
    }

    private fun updateServiceButtonState() {
        if (AudioCaptureService.isRunning) {
            binding.btnToggleService.text = "Stop Subtitle Service"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        } else {
            binding.btnToggleService.text = "Start Subtitle Service"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.purple_500))
        }
    }

    private fun checkOfflineModelStatus() {
        val modelDir = File(filesDir, "models/whisper-tiny")
        val encoder = File(modelDir, "tiny-encoder.int8.onnx")
        val decoder = File(modelDir, "tiny-decoder.int8.onnx")
        val tokens = File(modelDir, "tiny-tokens.txt")

        if (encoder.exists() && decoder.exists() && tokens.exists()) {
            binding.tvModelStatus.text = "Offline Whisper Model Ready ✓ (${(encoder.length() + decoder.length()) / 1024 / 1024} MB)"
            binding.tvModelStatus.setTextColor(ContextCompat.getColor(this, R.color.teal_700))
            binding.btnDownloadModel.visibility = View.GONE
        } else {
            binding.tvModelStatus.text = "Offline Whisper model not downloaded (~75 MB)."
            binding.tvModelStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            binding.btnDownloadModel.visibility = View.VISIBLE
        }
    }

    private fun downloadWhisperModel() {
        binding.progressBarModel.visibility = View.VISIBLE
        binding.btnDownloadModel.isEnabled = false
        binding.tvModelStatus.text = "Downloading Whisper model files (~75 MB)..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(filesDir, "models/whisper-tiny").apply { mkdirs() }
                val baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"

                val files = listOf(
                    "tiny-tokens.txt",
                    "tiny-encoder.int8.onnx",
                    "tiny-decoder.int8.onnx"
                )

                for ((index, fileName) in files.withIndex()) {
                    withContext(Dispatchers.Main) {
                        binding.tvModelStatus.text = "Downloading ${index + 1}/${files.size}: $fileName..."
                    }

                    val destFile = File(modelDir, fileName)
                    if (!destFile.exists() || destFile.length() < 100) {
                        downloadWithRedirects("$baseUrl/$fileName", destFile)
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.progressBarModel.visibility = View.GONE
                    binding.btnDownloadModel.isEnabled = true
                    checkOfflineModelStatus()
                    Toast.makeText(this@MainActivity, "Offline Whisper model ready to use!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBarModel.visibility = View.GONE
                    binding.btnDownloadModel.isEnabled = true
                    binding.tvModelStatus.text = "Download failed: ${e.message}"
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadWithRedirects(urlStr: String, destFile: File) {
        var currentUrl = urlStr
        var connection: HttpURLConnection
        var redirectCount = 0

        while (redirectCount < 5) {
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 40000
            connection.instanceFollowRedirects = false // Manually handle 302/307 to CDN
            val code = connection.responseCode

            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                if (location != null) {
                    currentUrl = location
                    redirectCount++
                    continue
                }
            }

            if (code == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return
            } else {
                throw Exception("HTTP $code from $currentUrl")
            }
        }
        throw Exception("Too many redirects")
    }

    private fun startAudioService(resultCode: Int, resultData: Intent) {
        val engineType = if (binding.rbCloud.isChecked) {
            AudioCaptureService.ENGINE_HYBRID_CLOUD
        } else {
            AudioCaptureService.ENGINE_OFFLINE
        }

        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""

        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_RESULT_DATA, resultData)
            putExtra(AudioCaptureService.EXTRA_ENGINE_TYPE, engineType)
            putExtra(AudioCaptureService.EXTRA_API_KEY, apiKey)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        updateServiceButtonState()
        Toast.makeText(this, "Live Subtitles started! Look for floating overlay on screen.", Toast.LENGTH_LONG).show()
    }

    private fun stopAudioService() {
        val serviceIntent = Intent(this, AudioCaptureService::class.java)
        stopService(serviceIntent)
        updateServiceButtonState()
        Toast.makeText(this, "Live Subtitles stopped.", Toast.LENGTH_SHORT).show()
    }
}
