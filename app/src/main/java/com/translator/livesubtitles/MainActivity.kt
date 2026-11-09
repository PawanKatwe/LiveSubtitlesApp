package com.translator.livesubtitles

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import com.translator.livesubtitles.databinding.ActivityMainBinding
import com.translator.livesubtitles.service.AudioCaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Permission launchers
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
            Toast.makeText(this, "Media projection permission cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "Audio recording permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupUI()
        checkAppPermissions()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
        updateServiceButtonState()
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
            } else {
                binding.layoutApiKey.visibility = View.GONE
            }
        }

        binding.btnToggleService.setOnClickListener {
            if (AudioCaptureService.isRunning) {
                stopAudioService()
            } else {
                if (!checkOverlayPermission()) {
                    Toast.makeText(this, "Please grant Overlay permission first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
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
            binding.btnGrantOverlay.text = "Permission Granted"
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
        Toast.makeText(this, "Live Subtitles started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopAudioService() {
        val serviceIntent = Intent(this, AudioCaptureService::class.java)
        stopService(serviceIntent)
        updateServiceButtonState()
        Toast.makeText(this, "Live Subtitles stopped.", Toast.LENGTH_SHORT).show()
    }
}
