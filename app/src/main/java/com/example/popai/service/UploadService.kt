package com.example.popai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.popai.R
import com.example.popai.config.EnvironmentConfig
import com.example.popai.database.*
import com.example.popai.s3.S3Config
import com.example.popai.s3.S3Uploader
import com.example.popai.s3.UploadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UploadService : LifecycleService() {

    private lateinit var database: AppDatabase
    private lateinit var uploader: S3Uploader
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "upload_channel"
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        initializeUploader()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val recordingId = intent?.getStringExtra("recording_id")
        if (recordingId != null) {
            startForeground(NOTIFICATION_ID, createNotification("Preparing upload..."))
            uploadRecording(recordingId)
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Upload Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows upload progress"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uploading recordings")
            .setContentText(message)
            .setSmallIcon(R.drawable.recording_indicator)
            .setOngoing(true)
            .build()

    private fun initializeUploader() {
        try {
            EnvironmentConfig.load(applicationContext)
            val config = S3Config(
                bucketName = EnvironmentConfig.s3BucketName,
                region = EnvironmentConfig.awsRegion,
                accessKey = EnvironmentConfig.awsAccessKeyId,
                secretKey = EnvironmentConfig.awsSecretAccessKey,
                sessionToken = EnvironmentConfig.awsSessionToken
            )
            uploader = S3Uploader(config)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun uploadRecording(recordingId: String) {
        lifecycleScope.launch {
            try {
                val recording = database.recordingDao().getRecordingById(recordingId)
                if (recording == null) {
                    stopSelf()
                    return@launch
                }

                // Update recording status
                database.recordingDao().updateRecording(
                    recording.copy(status = RecordingStatus.UPLOADING)
                )

                // Get all chunks
                val chunks = database.chunkDao().getChunksForRecordingSync(recordingId)
                var uploadedCount = 0
                var failedCount = 0

                for ((index, chunk) in chunks.withIndex()) {
                    if (chunk.uploadStatus == UploadStatus.UPLOADED) {
                        uploadedCount++
                        continue
                    }

                    val progress = "${index + 1}/${chunks.size}"
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification("Uploading chunk $progress")
                    )

                    val success = uploadChunk(chunk)
                    if (success) {
                        uploadedCount++
                    } else {
                        failedCount++
                    }
                }

                // Update final recording status
                val finalStatus = when {
                    failedCount == 0 -> RecordingStatus.UPLOADED
                    uploadedCount == 0 -> RecordingStatus.FAILED
                    else -> RecordingStatus.PARTIAL
                }

                database.recordingDao().updateRecording(
                    recording.copy(
                        status = finalStatus,
                        uploadedChunks = uploadedCount,
                        failedChunks = failedCount
                    )
                )

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private suspend fun uploadChunk(chunk: ChunkEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            // Update status to uploading
            database.chunkDao().updateChunk(
                chunk.copy(uploadStatus = UploadStatus.UPLOADING)
            )

            val file = File(chunk.encryptedFilePath)
            if (!file.exists()) {
                database.chunkDao().updateChunk(
                    chunk.copy(
                        uploadStatus = UploadStatus.FAILED,
                        uploadAttempts = chunk.uploadAttempts + 1,
                        lastError = "File not found"
                    )
                )
                return@withContext false
            }

            val s3Key = "medical-recordings/${chunk.recordingId}/chunk_${chunk.chunkIndex}.encrypted"

            val result = uploader.uploadFile(
                file = file,
                key = s3Key,
                contentType = "application/octet-stream"
            )

            when (result) {
                is UploadResult.Success -> {
                    database.chunkDao().updateChunk(
                        chunk.copy(
                            uploadStatus = UploadStatus.UPLOADED,
                            s3Key = result.key,
                            s3Url = result.url,
                            uploadAttempts = chunk.uploadAttempts + 1
                        )
                    )
                    true
                }
                is UploadResult.Failure -> {
                    val newAttempts = chunk.uploadAttempts + 1
                    val status = if (newAttempts >= MAX_RETRY_ATTEMPTS) {
                        UploadStatus.FAILED
                    } else {
                        UploadStatus.PENDING
                    }

                    database.chunkDao().updateChunk(
                        chunk.copy(
                            uploadStatus = status,
                            uploadAttempts = newAttempts,
                            lastError = result.error.message
                        )
                    )
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            database.chunkDao().updateChunk(
                chunk.copy(
                    uploadStatus = UploadStatus.FAILED,
                    uploadAttempts = chunk.uploadAttempts + 1,
                    lastError = e.message
                )
            )
            false
        }
    }

    suspend fun retryFailedUploads(recordingId: String) {
        val failedChunks = database.chunkDao().getChunksByRecordingAndStatus(
            recordingId,
            UploadStatus.FAILED
        )

        // Reset failed chunks to pending if they haven't exceeded max retries
        failedChunks.forEach { chunk ->
            if (chunk.uploadAttempts < MAX_RETRY_ATTEMPTS) {
                database.chunkDao().updateChunk(
                    chunk.copy(
                        uploadStatus = UploadStatus.PENDING,
                        lastError = null
                    )
                )
            }
        }

        // Restart upload
        val intent = Intent(this, UploadService::class.java).apply {
            putExtra("recording_id", recordingId)
        }
        startService(intent)
    }
}
