package com.example.popai

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.popai.config.EnvironmentConfig
import com.example.popai.databinding.ActivityMainBinding
import com.example.popai.s3.S3Config
import com.example.popai.service.RecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var configLoaded = false
    private var recordingService: RecordingService? = null
    private var isServiceBound = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
            isServiceBound = true
            updateUIState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize configuration
        initializeConfig()

        // Request permissions
        checkAndRequestPermissions()

        // Set up buttons
        binding.recordButton.setOnClickListener {
            showPatientInfoDialog()
        }

        binding.pauseButton.setOnClickListener {
            togglePause()
        }

        binding.stopButton.setOnClickListener {
            stopRecording()
        }

        binding.historyButton.setOnClickListener {
            openRecordingsHistory()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun initializeConfig() {
        lifecycleScope.launch {
            try {
                // Load .env from app assets (bundled with APK)
                val loaded = EnvironmentConfig.load(applicationContext)

                if (!loaded) {
                    showConfigError("Configuration Error", "Unable to load credentials. Please contact support.")
                    return@launch
                }

                // Check if credentials are present
                if (!EnvironmentConfig.hasRequiredCredentials()) {
                    showConfigError("Configuration Error", "Missing required credentials. Please contact support.")
                    return@launch
                }

                // Create S3 config
                val config = S3Config(
                    bucketName = EnvironmentConfig.s3BucketName,
                    region = EnvironmentConfig.awsRegion,
                    accessKey = EnvironmentConfig.awsAccessKeyId,
                    secretKey = EnvironmentConfig.awsSecretAccessKey,
                    sessionToken = EnvironmentConfig.awsSessionToken
                )

                // Show success
                showConfigSuccess()
                configLoaded = true
                binding.recordButton.isEnabled = true

            } catch (e: Exception) {
                showConfigError("Initialization Error", "Failed to initialize. Please try again.")
                e.printStackTrace()
            }
        }
    }

    private fun showConfigSuccess() {
        binding.configStatusText.text = "Ready to record medical conversations"
        binding.configStatusText.setTextColor(getColor(R.color.success))
    }

    private fun showConfigError(title: String, message: String) {
        binding.configStatusText.text = "$title: $message"
        binding.configStatusText.setTextColor(getColor(R.color.error))
        binding.recordButton.isEnabled = false
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                showStatusMessage("Permissions required for recording", isError = true)
            }
        }
    }

    private fun showPatientInfoDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_patient_info, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val patientNameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.patientNameInput)
        val healthcareProfessionalInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.healthcareProfessionalInput)
        val startButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.startRecordingButton)
        val cancelButton = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.cancelButton)

        startButton.setOnClickListener {
            val patientName = patientNameInput.text.toString().trim()
            val healthcareProfessional = healthcareProfessionalInput.text.toString().trim()

            if (patientName.isEmpty() || healthcareProfessional.isEmpty()) {
                showStatusMessage("Please fill in all fields", isError = true)
                return@setOnClickListener
            }

            dialog.dismiss()
            startRecording(patientName, healthcareProfessional)
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startRecording(patientName: String, healthcareProfessional: String) {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
            putExtra(RecordingService.EXTRA_PATIENT_NAME, patientName)
            putExtra(RecordingService.EXTRA_HEALTHCARE_PROFESSIONAL, healthcareProfessional)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(Intent(this, RecordingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        lifecycleScope.launch {
            delay(500)
            updateUIState()
            startTimerUpdate()
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        startService(intent)
        updateUIState()
    }

    private fun togglePause() {
        val service = recordingService ?: return
        if (service.isPaused()) {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_RESUME_RECORDING
            }
            startService(intent)
            binding.pauseButton.text = "Pause"
        } else {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_PAUSE_RECORDING
            }
            startService(intent)
            binding.pauseButton.text = "Resume"
        }
    }

    private fun updateUIState() {
        val isRecording = recordingService?.isRecording() ?: false
        if (isRecording) {
            binding.initialControls.visibility = View.GONE
            binding.recordingControls.visibility = View.VISIBLE
            binding.recordingTimer.visibility = View.VISIBLE

            val isPaused = recordingService?.isPaused() ?: false
            binding.pauseButton.text = if (isPaused) "Resume" else "Pause"
        } else {
            binding.initialControls.visibility = View.VISIBLE
            binding.recordingControls.visibility = View.GONE
            binding.recordingTimer.visibility = View.GONE
        }
    }

    private fun startTimerUpdate() {
        lifecycleScope.launch {
            while (recordingService?.isRecording() == true) {
                val duration = recordingService?.getRecordingDuration() ?: 0
                val hours = (duration / 1000 / 3600).toInt()
                val minutes = ((duration / 1000) % 3600 / 60).toInt()
                val seconds = ((duration / 1000) % 60).toInt()
                binding.recordingTimer.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                delay(1000)
            }
        }
    }

    private fun openRecordingsHistory() {
        val intent = Intent(this, RecordingsActivity::class.java)
        startActivity(intent)
    }

    private fun showStatusMessage(message: String, isError: Boolean) {
        runOnUiThread {
            binding.statusText.text = message
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.setTextColor(getColor(if (isError) R.color.error else R.color.success))

            // Hide message after 5 seconds
            binding.statusText.postDelayed({
                binding.statusText.visibility = View.GONE
            }, 5000)
        }
    }
}
