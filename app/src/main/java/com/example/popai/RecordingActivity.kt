package com.example.popai

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.popai.config.EnvironmentConfig
import com.example.popai.databinding.ActivityRecordingBinding
import com.example.popai.s3.S3Config
import com.example.popai.s3.S3Uploader
import com.example.popai.s3.UploadResult
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingBinding
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var isPaused = false
    private var recordingStartTime = 0L
    private var pausedDuration = 0L
    private var pauseStartTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording && !isPaused) {
                val elapsed = System.currentTimeMillis() - recordingStartTime - pausedDuration
                updateTimer(elapsed)
                handler.postDelayed(this, 100)
            }
        }
    }

    private lateinit var patientName: String
    private lateinit var providerName: String
    private var notes: String = ""

    private lateinit var s3Uploader: S3Uploader

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get patient info from intent
        patientName = intent.getStringExtra("PATIENT_NAME") ?: "Unknown Patient"
        providerName = intent.getStringExtra("PROVIDER_NAME") ?: "Unknown Provider"
        notes = intent.getStringExtra("NOTES") ?: ""

        binding.patientNameText.text = "Patient: $patientName"
        binding.providerNameText.text = "Provider: $providerName"

        // Initialize S3 uploader
        initializeS3Uploader()

        // Setup buttons
        binding.recordButton.setOnClickListener {
            if (!isRecording) {
                startRecording()
            } else if (isPaused) {
                resumeRecording()
            }
        }

        binding.pauseButton.setOnClickListener {
            pauseRecording()
        }

        binding.stopButton.setOnClickListener {
            stopRecording()
        }

        // Check permissions
        if (!checkPermissions()) {
            requestPermissions()
        }
    }

    private fun initializeS3Uploader() {
        // Load environment config
        EnvironmentConfig.load(applicationContext)

        val config = S3Config(
            bucketName = EnvironmentConfig.s3BucketName,
            region = EnvironmentConfig.awsRegion,
            accessKey = EnvironmentConfig.awsAccessKeyId,
            secretKey = EnvironmentConfig.awsSecretAccessKey,
            sessionToken = EnvironmentConfig.awsSessionToken
        )

        s3Uploader = S3Uploader(config)
        appendLog("S3 Uploader initialized")
    }

    private fun checkPermissions(): Boolean {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return permission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    appendLog("Audio recording permission denied")
                    finish()
                } else {
                    appendLog("Audio recording permission granted")
                }
            }
        }
    }

    private fun startRecording() {
        if (!checkPermissions()) {
            appendLog("Please grant audio recording permission")
            requestPermissions()
            return
        }

        try {
            // Create recording file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${patientName.replace(" ", "_")}_${providerName.replace(" ", "_")}_$timestamp.m4a"
            recordingFile = File(cacheDir, fileName)

            appendLog("Starting recording: $fileName")

            // Initialize MediaRecorder
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(recordingFile!!.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            isPaused = false
            recordingStartTime = System.currentTimeMillis()
            pausedDuration = 0L

            updateUI()
            handler.post(timerRunnable)

            appendLog("Recording started")

        } catch (e: Exception) {
            appendLog("Error starting recording: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun pauseRecording() {
        if (isRecording && !isPaused) {
            try {
                mediaRecorder?.pause()
                isPaused = true
                pauseStartTime = System.currentTimeMillis()
                updateUI()
                appendLog("Recording paused")
            } catch (e: Exception) {
                appendLog("Error pausing: ${e.message}")
            }
        }
    }

    private fun resumeRecording() {
        if (isRecording && isPaused) {
            try {
                mediaRecorder?.resume()
                isPaused = false
                pausedDuration += System.currentTimeMillis() - pauseStartTime
                updateUI()
                handler.post(timerRunnable)
                appendLog("Recording resumed")
            } catch (e: Exception) {
                appendLog("Error resuming: ${e.message}")
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            isRecording = false
            isPaused = false
            handler.removeCallbacks(timerRunnable)

            updateUI()
            appendLog("Recording stopped")

            // Upload to S3
            recordingFile?.let { uploadToS3(it) }

        } catch (e: Exception) {
            appendLog("Error stopping recording: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun uploadToS3(file: File) {
        appendLog("Preparing to upload to S3...")
        appendLog("File: ${file.name} (${file.length()} bytes)")

        lifecycleScope.launch {
            try {
                binding.recordButton.isEnabled = false
                binding.stopButton.isEnabled = false

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val s3Key = "medical-recordings/${patientName.replace(" ", "_")}/$timestamp/${file.name}"

                appendLog("Uploading to S3 key: $s3Key")

                val result = s3Uploader.uploadFile(
                    file = file,
                    key = s3Key,
                    contentType = "audio/mp4"
                )

                when (result) {
                    is UploadResult.Success -> {
                        appendLog("✓ Upload SUCCESSFUL!")
                        appendLog("S3 Key: ${result.key}")
                        appendLog("URL: ${result.url}")
                        appendLog("\nRecording saved securely to cloud")

                        // Clean up local file
                        file.delete()
                        appendLog("Local file cleaned up")

                        // Return to patient info screen
                        handler.postDelayed({
                            finish()
                        }, 2000)
                    }
                    is UploadResult.Failure -> {
                        appendLog("✗ Upload FAILED!")
                        appendLog("Error: ${result.error.message}")
                        appendLog("\nRecording saved locally at: ${file.absolutePath}")
                    }
                }

            } catch (e: Exception) {
                appendLog("✗ Upload exception: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun updateTimer(elapsedMillis: Long) {
        val seconds = (elapsedMillis / 1000) % 60
        val minutes = (elapsedMillis / (1000 * 60)) % 60
        val hours = (elapsedMillis / (1000 * 60 * 60))

        binding.timerText.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun updateUI() {
        when {
            !isRecording -> {
                binding.statusText.text = "Ready to Record"
                binding.recordingIndicator.visibility = View.GONE
                binding.recordButton.visibility = View.VISIBLE
                binding.pauseButton.visibility = View.GONE
                binding.stopButton.isEnabled = false
                binding.timerText.text = "00:00:00"
            }
            isPaused -> {
                binding.statusText.text = "Recording Paused"
                binding.recordingIndicator.visibility = View.GONE
                binding.recordButton.visibility = View.VISIBLE
                binding.pauseButton.visibility = View.GONE
                binding.stopButton.isEnabled = true
            }
            else -> {
                binding.statusText.text = "Recording..."
                binding.recordingIndicator.visibility = View.VISIBLE
                binding.recordButton.visibility = View.GONE
                binding.pauseButton.visibility = View.VISIBLE
                binding.stopButton.isEnabled = true
            }
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val current = binding.logText.text.toString()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            binding.logText.text = "$current\n[$timestamp] $message"

            // Auto-scroll to bottom
            binding.logText.post {
                val scrollView = binding.logText.parent as? android.widget.ScrollView
                scrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.release()
        mediaRecorder = null
        handler.removeCallbacks(timerRunnable)
    }
}
