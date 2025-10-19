package com.example.popai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.popai.databinding.ActivityMainBinding
import com.example.popai.service.RecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var recordingService: RecordingService? = null
    private var isServiceBound = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        const val UPLOAD_LOG_ACTION = "com.example.popai.UPLOAD_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }

    private val uploadLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(EXTRA_LOG_MESSAGE)
            message?.let {
                appendLog(it)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.RecordingBinder
            recordingService = binder.getService()
            isServiceBound = true
            updateUIState()

            // If recording is in progress, start timer update
            if (recordingService?.isRecording() == true) {
                startTimerUpdate()
            }
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

        // Request permissions
        checkAndRequestPermissions()

        // Check if recording service is already running
        bindToExistingService()

        // Show ready status
        binding.configStatusText.text = "Ready to record medical conversations"
        binding.configStatusText.setTextColor(getColor(R.color.success))
        binding.recordButton.isEnabled = true

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

    override fun onResume() {
        super.onResume()
        // Re-bind to service when app comes to foreground
        bindToExistingService()

        // Register broadcast receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uploadLogReceiver, IntentFilter(UPLOAD_LOG_ACTION), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uploadLogReceiver, IntentFilter(UPLOAD_LOG_ACTION))
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(uploadLogReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun bindToExistingService() {
        val intent = Intent(this, RecordingService::class.java)
        try {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
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
        // Immediately disable buttons and show feedback
        binding.stopButton.isEnabled = false
        binding.pauseButton.isEnabled = false
        binding.stopButton.text = "Stopping..."

        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        startService(intent)

        // Poll UI state to update when stopping is complete
        lifecycleScope.launch {
            var attempts = 0
            while (attempts < 20) { // Try for up to 10 seconds
                delay(500)
                val isStopping = recordingService?.isStopping() ?: false
                if (!isStopping) {
                    // Stopping is complete
                    updateUIState()
                    break
                }
                attempts++
            }
            // Force update UI after timeout
            if (attempts >= 20) {
                updateUIState()
            }
        }
    }

    private fun togglePause() {
        val service = recordingService ?: return

        if (service.isPaused()) {
            // Currently paused, so resume
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_RESUME_RECORDING
            }
            startService(intent)

            // Update UI after a brief delay to allow service to process
            lifecycleScope.launch {
                delay(300)
                updateUIState() // Use updateUIState to sync properly
            }
        } else {
            // Currently recording, so pause
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_PAUSE_RECORDING
            }
            startService(intent)

            // Update UI after a brief delay to allow service to process
            lifecycleScope.launch {
                delay(300)
                updateUIState() // Use updateUIState to sync properly
            }
        }
    }

    private fun startWaveformAnimation() {
        val drawable = binding.waveformAnimation.drawable
        if (drawable is Animatable) {
            drawable.start()
        }
    }

    private fun stopWaveformAnimation() {
        val drawable = binding.waveformAnimation.drawable
        if (drawable is Animatable) {
            drawable.stop()
        }
    }

    private fun updateUIState() {
        val isRecording = recordingService?.isRecording() ?: false
        val isStopping = recordingService?.isStopping() ?: false

        if (isRecording) {
            binding.initialControls.visibility = View.GONE
            binding.recordingControls.visibility = View.VISIBLE
            binding.recordingCard.visibility = View.VISIBLE

            val isPaused = recordingService?.isPaused() ?: false
            binding.pauseButton.text = if (isPaused) "Resume" else "Pause"

            // Enable/disable buttons based on stopping state
            binding.stopButton.isEnabled = !isStopping
            binding.pauseButton.isEnabled = !isStopping
            binding.stopButton.text = if (isStopping) "Stopping..." else "Stop"

            // Start animation if not paused and not stopping
            if (!isPaused && !isStopping) {
                startWaveformAnimation()
            } else {
                stopWaveformAnimation()
            }
        } else {
            binding.initialControls.visibility = View.VISIBLE
            binding.recordingControls.visibility = View.GONE
            binding.recordingCard.visibility = View.GONE
            stopWaveformAnimation()

            // Reset button states
            binding.stopButton.isEnabled = true
            binding.pauseButton.isEnabled = true
            binding.stopButton.text = "Stop"
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
            // Show a toast message instead of using statusText
            android.widget.Toast.makeText(
                this,
                message,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            // Log to Android logcat for debugging
            android.util.Log.d("UploadService", message)

            // Optionally show in UI (you can enable/disable this)
            // android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
