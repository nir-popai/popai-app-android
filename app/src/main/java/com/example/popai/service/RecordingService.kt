package com.example.popai.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.popai.MainActivity
import com.example.popai.R
import com.example.popai.database.*
import com.example.popai.encryption.EncryptionManager
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class RecordingService : LifecycleService() {

    private val binder = RecordingBinder()
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingId: String? = null
    private var patientName: String = ""
    private var healthcareProfessional: String = ""
    private var currentChunkIndex = 0
    private var recordingStartTime: Long = 0
    private var chunkStartTime: Long = 0
    private var chunkTimer: Job? = null
    private var isPaused = false
    private var pauseStartTime: Long = 0
    private var totalPausedTime: Long = 0
    private var isStopping = false

    private lateinit var database: AppDatabase
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"
        private const val CHUNK_DURATION_MS = 5 * 60 * 1000L // 5 minutes (smaller chunks = faster, more reliable uploads)

        const val ACTION_START_RECORDING = "start_recording"
        const val ACTION_STOP_RECORDING = "stop_recording"
        const val ACTION_PAUSE_RECORDING = "pause_recording"
        const val ACTION_RESUME_RECORDING = "resume_recording"
        const val EXTRA_PATIENT_NAME = "patient_name"
        const val EXTRA_HEALTHCARE_PROFESSIONAL = "healthcare_professional"
    }

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(applicationContext)
        encryptionManager = EncryptionManager(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                patientName = intent.getStringExtra(EXTRA_PATIENT_NAME) ?: ""
                healthcareProfessional = intent.getStringExtra(EXTRA_HEALTHCARE_PROFESSIONAL) ?: ""
                startRecording()
            }
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_PAUSE_RECORDING -> pauseRecording()
            ACTION_RESUME_RECORDING -> resumeRecording()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(recordingDuration: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, RecordingService::class.java).apply {
            action = if (isPaused) ACTION_RESUME_RECORDING else ACTION_PAUSE_RECORDING
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isPaused) "Paused - $recordingDuration" else "Duration: $recordingDuration"
        val pauseResumeText = if (isPaused) "Resume" else "Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording in progress")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.recording_indicator)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.recording_indicator, pauseResumeText, pauseResumePendingIntent)
            .addAction(R.drawable.recording_indicator, "Stop", stopPendingIntent)
            .build()
    }

    fun startRecording() {
        if (currentRecordingId != null) {
            return // Already recording
        }

        currentRecordingId = UUID.randomUUID().toString()
        currentChunkIndex = 0
        recordingStartTime = System.currentTimeMillis()

        lifecycleScope.launch {
            // Create recording entry
            val recording = RecordingEntity(
                id = currentRecordingId!!,
                patientName = patientName,
                healthcareProfessional = healthcareProfessional,
                startTime = recordingStartTime,
                endTime = null,
                totalDurationMs = 0,
                pausedDurationMs = 0,
                status = RecordingStatus.RECORDING,
                chunkCount = 0,
                uploadedChunks = 0,
                failedChunks = 0,
                errorMessage = null
            )
            database.recordingDao().insertRecording(recording)
        }

        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification("00:00"))

        // Start first chunk
        startNewChunk()

        // Schedule chunk rotation
        scheduleChunkRotation()

        // Update notification periodically
        updateNotificationPeriodically()
    }

    fun stopRecording() {
        // Prevent multiple stop calls
        if (isStopping) {
            android.util.Log.d("RecordingService", "Stop already in progress, ignoring duplicate stop request")
            return
        }
        isStopping = true

        chunkTimer?.cancel()

        lifecycleScope.launch {
            try {
                // Stop and encrypt current chunk
                try {
                    mediaRecorder?.apply {
                        stop()
                        release()
                    }
                    mediaRecorder = null

                    // Give MediaRecorder time to finalize the file
                    delay(500)

                    encryptCurrentChunk()
                    currentChunkIndex++
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                currentRecordingId?.let { recordingId ->
                    val recording = database.recordingDao().getRecordingById(recordingId)
                    recording?.let {
                        val updatedRecording = it.copy(
                            endTime = System.currentTimeMillis(),
                            totalDurationMs = System.currentTimeMillis() - it.startTime - totalPausedTime,
                            pausedDurationMs = totalPausedTime,
                            status = RecordingStatus.COMPLETED,
                            chunkCount = currentChunkIndex
                        )
                        database.recordingDao().updateRecording(updatedRecording)
                    }

                    // Start upload process
                    startUploadService(recordingId)
                }

                currentRecordingId = null
                isPaused = false
                totalPausedTime = 0

            } finally {
                // Always reset stopping flag and stop service
                isStopping = false
                withContext(Dispatchers.Main) {
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
    }

    private fun startNewChunk() {
        try {
            chunkStartTime = System.currentTimeMillis()

            val recordingDir = File(filesDir, "recordings")
            if (!recordingDir.exists()) {
                recordingDir.mkdirs()
            }

            val chunkFile = File(recordingDir, "${currentRecordingId}_chunk_$currentChunkIndex.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(chunkFile.absolutePath)
                prepare()
                start()
            }

            lifecycleScope.launch {
                val chunk = ChunkEntity(
                    id = UUID.randomUUID().toString(),
                    recordingId = currentRecordingId!!,
                    chunkIndex = currentChunkIndex,
                    localFilePath = chunkFile.absolutePath,
                    encryptedFilePath = "",
                    durationMs = 0,
                    fileSize = 0,
                    uploadStatus = UploadStatus.PENDING,
                    s3Key = null,
                    s3Url = null,
                    uploadAttempts = 0,
                    lastError = null
                )
                database.chunkDao().insertChunk(chunk)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun stopCurrentChunk() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            // Give MediaRecorder time to finalize the file
            delay(500)

            // Encrypt the chunk and wait for it to complete
            encryptCurrentChunk()

            // Only increment after encryption is complete
            currentChunkIndex++
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun encryptCurrentChunk() {
        withContext(Dispatchers.IO) {
            val chunks = database.chunkDao().getChunksForRecordingSync(currentRecordingId!!)
            val lastChunk = chunks.lastOrNull()

            if (lastChunk == null) {
                android.util.Log.e("RecordingService", "encryptCurrentChunk: No chunk found for recording $currentRecordingId")
                return@withContext
            }

            val inputFile = File(lastChunk.localFilePath)
            val duration = System.currentTimeMillis() - chunkStartTime

            android.util.Log.d("RecordingService", "Encrypting chunk ${lastChunk.chunkIndex}: path=${lastChunk.localFilePath}, exists=${inputFile.exists()}")

            if (!inputFile.exists()) {
                // File doesn't exist - update chunk with error
                android.util.Log.e("RecordingService", "Audio file NOT FOUND: ${lastChunk.localFilePath}")
                val updatedChunk = lastChunk.copy(
                    durationMs = duration,
                    fileSize = 0,
                    uploadStatus = UploadStatus.FAILED,
                    lastError = "Audio file not found after recording stopped"
                )
                database.chunkDao().updateChunk(updatedChunk)
                return@withContext
            }

            val fileSize = inputFile.length()
            android.util.Log.d("RecordingService", "Audio file size: $fileSize bytes, duration: $duration ms")

            if (fileSize == 0L) {
                // File is empty - update chunk with error
                android.util.Log.e("RecordingService", "Audio file is EMPTY (0 bytes): ${lastChunk.localFilePath}")
                val updatedChunk = lastChunk.copy(
                    durationMs = duration,
                    fileSize = 0,
                    uploadStatus = UploadStatus.FAILED,
                    lastError = "Audio file is empty (0 bytes)"
                )
                database.chunkDao().updateChunk(updatedChunk)
                return@withContext
            }

            // File exists and has content - update duration and size first
            android.util.Log.d("RecordingService", "Updating chunk metadata: size=$fileSize, duration=$duration")
            database.chunkDao().updateChunk(
                lastChunk.copy(
                    durationMs = duration,
                    fileSize = fileSize
                )
            )

            // Then attempt encryption
            val encryptedFile = File(inputFile.parent, "${inputFile.nameWithoutExtension}.encrypted")
            android.util.Log.d("RecordingService", "Attempting encryption to: ${encryptedFile.absolutePath}")
            val encrypted = encryptionManager.encryptFile(inputFile, encryptedFile)

            if (encrypted) {
                android.util.Log.d("RecordingService", "Encryption successful")
                val updatedChunk = lastChunk.copy(
                    encryptedFilePath = encryptedFile.absolutePath,
                    durationMs = duration,
                    fileSize = fileSize
                )
                database.chunkDao().updateChunk(updatedChunk)
                // Keep raw file for upload - don't delete it
                // Raw files will be deleted after successful upload
            } else {
                android.util.Log.e("RecordingService", "Encryption FAILED")
            }
        }
    }

    private fun scheduleChunkRotation() {
        chunkTimer = lifecycleScope.launch {
            while (isActive) {
                delay(CHUNK_DURATION_MS)
                if (currentRecordingId != null) {
                    stopCurrentChunk()
                    startNewChunk()
                }
            }
        }
    }

    private fun updateNotificationPeriodically() {
        lifecycleScope.launch {
            while (currentRecordingId != null) {
                delay(1000)
                val currentPausedTime = if (isPaused) {
                    totalPausedTime + (System.currentTimeMillis() - pauseStartTime)
                } else {
                    totalPausedTime
                }
                val duration = System.currentTimeMillis() - recordingStartTime - currentPausedTime
                val minutes = (duration / 1000 / 60).toInt()
                val seconds = (duration / 1000 % 60).toInt()
                val timeString = String.format("%02d:%02d", minutes, seconds)

                notificationManager.notify(NOTIFICATION_ID, createNotification(timeString))
            }
        }
    }

    private fun startUploadService(recordingId: String) {
        val intent = Intent(this, UploadService::class.java).apply {
            putExtra("recording_id", recordingId)
        }
        startService(intent)
    }

    fun isRecording(): Boolean = currentRecordingId != null && !isStopping

    fun isStopping(): Boolean = isStopping

    fun getCurrentRecordingId(): String? = currentRecordingId

    fun getRecordingDuration(): Long {
        return if (currentRecordingId != null) {
            val currentPausedTime = if (isPaused) {
                totalPausedTime + (System.currentTimeMillis() - pauseStartTime)
            } else {
                totalPausedTime
            }
            System.currentTimeMillis() - recordingStartTime - currentPausedTime
        } else {
            0
        }
    }

    private fun pauseRecording() {
        if (!isRecording() || isPaused) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.pause()
                isPaused = true
                pauseStartTime = System.currentTimeMillis()

                // Update notification immediately
                val duration = System.currentTimeMillis() - recordingStartTime - totalPausedTime
                val minutes = (duration / 1000 / 60).toInt()
                val seconds = (duration / 1000 % 60).toInt()
                val timeString = String.format("%02d:%02d", minutes, seconds)
                notificationManager.notify(NOTIFICATION_ID, createNotification(timeString))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resumeRecording() {
        if (!isRecording() || !isPaused) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder?.resume()
                totalPausedTime += System.currentTimeMillis() - pauseStartTime
                isPaused = false

                // Update notification immediately
                val duration = System.currentTimeMillis() - recordingStartTime - totalPausedTime
                val minutes = (duration / 1000 / 60).toInt()
                val seconds = (duration / 1000 % 60).toInt()
                val timeString = String.format("%02d:%02d", minutes, seconds)
                notificationManager.notify(NOTIFICATION_ID, createNotification(timeString))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isPaused(): Boolean = this.isPaused
}
