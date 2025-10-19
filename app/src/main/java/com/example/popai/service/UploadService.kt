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
import com.example.popai.model.*
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Preparing upload..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification("Preparing upload..."))
            }
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

    private fun createNotification(message: String, progress: Int = -1) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uploading recordings")
            .setContentText(message)
            .setSmallIcon(R.drawable.recording_indicator)
            .setOngoing(true)
            .apply {
                if (progress >= 0) {
                    setProgress(100, progress, false)
                }
            }
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
                    broadcastLog("Recording not found: $recordingId")
                    stopSelf()
                    return@launch
                }

                broadcastLog("Starting upload for recording: ${recording.patientName}")

                // Get all chunks
                val chunks = database.chunkDao().getChunksForRecordingSync(recordingId)
                broadcastLog("Found ${chunks.size} chunks to upload")

                // Calculate total bytes
                val totalBytes = chunks.sumOf { it.fileSize }
                val totalMB = totalBytes / (1024.0 * 1024.0)
                broadcastLog("Total size: %.2f MB".format(totalMB))

                // Update recording status with total bytes
                database.recordingDao().updateRecording(
                    recording.copy(
                        status = RecordingStatus.UPLOADING,
                        totalBytes = totalBytes,
                        uploadedBytes = 0L,
                        currentChunkProgress = 0
                    )
                )

                var uploadedCount = 0
                var failedCount = 0
                var totalUploadedBytes = 0L

                for ((index, chunk) in chunks.withIndex()) {
                    if (chunk.uploadStatus == UploadStatus.UPLOADED) {
                        uploadedCount++
                        totalUploadedBytes += chunk.fileSize
                        broadcastLog("Chunk ${index + 1} already uploaded, skipping")
                        continue
                    }

                    val progress = "${index + 1}/${chunks.size}"
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification("Uploading chunk $progress")
                    )
                    broadcastLog("Uploading chunk $progress...")

                    val success = uploadChunk(chunk, recordingId, totalUploadedBytes)
                    if (success) {
                        uploadedCount++
                        totalUploadedBytes += chunk.fileSize
                        broadcastLog("✓ Chunk ${index + 1} uploaded successfully")
                    } else {
                        failedCount++
                        broadcastLog("✗ Chunk ${index + 1} failed to upload")
                    }
                }

                // Update final recording status
                val finalStatus = when {
                    failedCount == 0 -> RecordingStatus.UPLOADED
                    uploadedCount == 0 -> RecordingStatus.FAILED
                    else -> RecordingStatus.PARTIAL
                }

                val errorMessage = when (finalStatus) {
                    RecordingStatus.FAILED -> "All chunks failed to upload"
                    RecordingStatus.PARTIAL -> "Some chunks failed to upload"
                    else -> null
                }

                database.recordingDao().updateRecording(
                    recording.copy(
                        status = finalStatus,
                        uploadedChunks = uploadedCount,
                        failedChunks = failedCount,
                        errorMessage = errorMessage
                    )
                )

                broadcastLog("Upload complete: $uploadedCount succeeded, $failedCount failed (Status: $finalStatus)")

                // Generate and upload manifest if all chunks uploaded successfully
                if (finalStatus == RecordingStatus.UPLOADED) {
                    broadcastLog("Generating manifest file...")
                    // Re-query chunks from database to get updated upload status
                    val updatedChunks = database.chunkDao().getChunksForRecordingSync(recordingId)
                    broadcastLog("Re-queried ${updatedChunks.size} chunks from database")
                    updatedChunks.forEachIndexed { index, chunk ->
                        broadcastLog("  Chunk $index: status=${chunk.uploadStatus}, fileSize=${chunk.fileSize}, s3Key=${chunk.s3Key}")
                    }
                    val manifestSuccess = uploadManifest(recording, updatedChunks)
                    if (manifestSuccess) {
                        broadcastLog("✓ Manifest file uploaded successfully")
                    } else {
                        broadcastLog("✗ Failed to upload manifest file")
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                broadcastLog("Upload error: ${e.message}")

                // Update recording status to FAILED on exception
                try {
                    val recording = database.recordingDao().getRecordingById(recordingId)
                    recording?.let {
                        database.recordingDao().updateRecording(
                            it.copy(
                                status = RecordingStatus.FAILED,
                                errorMessage = "Upload service error: ${e.message}"
                            )
                        )
                    }
                } catch (dbError: Exception) {
                    dbError.printStackTrace()
                }
            } finally {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun broadcastLog(message: String) {
        val intent = Intent("com.example.popai.UPLOAD_LOG").apply {
            putExtra("log_message", message)
        }
        sendBroadcast(intent)
    }

    private suspend fun uploadChunk(
        chunk: ChunkEntity,
        recordingId: String,
        bytesUploadedSoFar: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Update status to uploading
            database.chunkDao().updateChunk(
                chunk.copy(uploadStatus = UploadStatus.UPLOADING)
            )

            // Upload raw file, not encrypted
            val file = File(chunk.localFilePath)
            if (!file.exists()) {
                broadcastLog("  Error: File not found at ${chunk.localFilePath}")
                database.chunkDao().updateChunk(
                    chunk.copy(
                        uploadStatus = UploadStatus.FAILED,
                        uploadAttempts = chunk.uploadAttempts + 1,
                        lastError = "File not found"
                    )
                )
                return@withContext false
            }

            val fileSize = file.length()
            if (fileSize == 0L) {
                broadcastLog("  Error: File is empty (0 bytes): ${chunk.localFilePath}")
                database.chunkDao().updateChunk(
                    chunk.copy(
                        uploadStatus = UploadStatus.FAILED,
                        uploadAttempts = chunk.uploadAttempts + 1,
                        lastError = "File is empty (0 bytes)"
                    )
                )
                return@withContext false
            }

            val s3Key = "medical-recordings/${chunk.recordingId}/chunk_${chunk.chunkIndex}.m4a"
            val fileSizeKB = fileSize / 1024
            val fileSizeMB = fileSize / (1024.0 * 1024.0)
            broadcastLog("  Uploading to S3: $s3Key (${fileSizeKB} KB)")

            val result = uploader.uploadFile(
                file = file,
                key = s3Key,
                contentType = "audio/mp4",
                onProgress = { progress ->
                    // Calculate total progress across all chunks
                    val currentUploadedBytes = bytesUploadedSoFar + progress.bytesTransferred

                    // Update notification with progress
                    val progressMessage = String.format(
                        "Chunk ${chunk.chunkIndex + 1}: %.2f/%.2f MB (%d%%)",
                        progress.megabytesTransferred,
                        progress.totalMegabytes,
                        progress.percentComplete
                    )
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification(progressMessage, progress.percentComplete)
                    )
                    broadcastLog("  Progress: $progressMessage")

                    // Update recording progress in database
                    lifecycleScope.launch(Dispatchers.IO) {
                        val recording = database.recordingDao().getRecordingById(recordingId)
                        recording?.let {
                            database.recordingDao().updateRecording(
                                it.copy(
                                    uploadedBytes = currentUploadedBytes,
                                    currentChunkProgress = progress.percentComplete
                                )
                            )
                        }
                    }
                }
            )

            when (result) {
                is UploadResult.Success -> {
                    broadcastLog("  S3 URL: ${result.url}")
                    database.chunkDao().updateChunk(
                        chunk.copy(
                            uploadStatus = UploadStatus.UPLOADED,
                            s3Key = result.key,
                            s3Url = result.url,
                            uploadAttempts = chunk.uploadAttempts + 1
                        )
                    )

                    // Delete raw file after successful upload
                    try {
                        file.delete()
                        broadcastLog("  Raw file deleted after upload")
                    } catch (e: Exception) {
                        broadcastLog("  Warning: Could not delete raw file: ${e.message}")
                    }

                    true
                }
                is UploadResult.Failure -> {
                    val newAttempts = chunk.uploadAttempts + 1
                    val status = if (newAttempts >= MAX_RETRY_ATTEMPTS) {
                        UploadStatus.FAILED
                    } else {
                        UploadStatus.PENDING
                    }

                    broadcastLog("  S3 Error: ${result.error.message} (attempt $newAttempts/$MAX_RETRY_ATTEMPTS)")

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
            broadcastLog("  Exception: ${e.message}")
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

    private suspend fun uploadManifest(
        recording: RecordingEntity,
        chunks: List<ChunkEntity>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            broadcastLog("uploadManifest: Received ${chunks.size} total chunks")

            // Debug: log all chunk statuses
            chunks.forEach { chunk ->
                broadcastLog("  Chunk ${chunk.chunkIndex}: status=${chunk.uploadStatus}, fileSize=${chunk.fileSize}, s3Key=${chunk.s3Key}")
            }

            // Get only uploaded chunks and sort by index
            val uploadedChunks = chunks
                .filter { it.uploadStatus == UploadStatus.UPLOADED }
                .sortedBy { it.chunkIndex }

            broadcastLog("uploadManifest: Filtered to ${uploadedChunks.size} UPLOADED chunks")

            if (uploadedChunks.isEmpty()) {
                broadcastLog("ERROR: No uploaded chunks found! Cannot generate manifest.")
                return@withContext false
            }

            // Calculate cumulative start times for each chunk
            var cumulativeTime = 0L
            val chunkInfoList = uploadedChunks.map { chunk ->
                val chunkInfo = ChunkInfo(
                    chunkIndex = chunk.chunkIndex,
                    durationMs = chunk.durationMs,
                    s3Key = chunk.s3Key ?: "",
                    sizeBytes = chunk.fileSize,
                    startTimeMs = cumulativeTime
                )
                broadcastLog("  Adding chunk ${chunk.chunkIndex} to manifest: size=${chunk.fileSize}, duration=${chunk.durationMs}")
                cumulativeTime += chunk.durationMs
                chunkInfo
            }

            // Calculate totals from uploaded chunks
            val totalSizeBytes = chunkInfoList.sumOf { it.sizeBytes }
            val totalDurationMs = chunkInfoList.sumOf { it.durationMs }

            // Create manifest
            val manifest = RecordingManifest(
                recordingId = recording.id,
                recordingStartDate = formatTimestamp(recording.startTime),
                uploadDate = formatTimestamp(System.currentTimeMillis()),
                metadata = RecordingMetadata(
                    patientName = recording.patientName,
                    healthcareProfessional = recording.healthcareProfessional
                ),
                chunks = chunkInfoList,
                summary = RecordingSummary(
                    totalChunks = chunkInfoList.size,
                    totalDurationMs = totalDurationMs,
                    totalSizeBytes = totalSizeBytes,
                    uploadedChunks = uploadedChunks.size,
                    failedChunks = chunks.size - uploadedChunks.size
                )
            )

            // Convert to JSON
            val manifestJson = manifest.toJson()
            broadcastLog("Manifest contains ${chunkInfoList.size} chunks")
            broadcastLog("Manifest JSON size: ${manifestJson.length} bytes")

            // Debug: Print the actual manifest JSON
            android.util.Log.d("UploadService", "Manifest JSON:\n$manifestJson")

            // Write to temporary file
            val manifestFile = File(cacheDir, "manifest_${recording.id}.json")
            manifestFile.writeText(manifestJson)

            // Upload to S3
            val s3Key = "medical-recordings/${recording.id}/manifest.json"
            broadcastLog("Uploading manifest to S3: $s3Key")

            val result = uploader.uploadFile(
                file = manifestFile,
                key = s3Key,
                contentType = "application/json",
                onProgress = { progress ->
                    val progressMessage = String.format(
                        "Manifest: %.2f/%.2f KB (%d%%)",
                        progress.megabytesTransferred * 1024,
                        progress.totalMegabytes * 1024,
                        progress.percentComplete
                    )
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        createNotification(progressMessage, progress.percentComplete)
                    )
                }
            )

            // Clean up temp file
            manifestFile.delete()

            when (result) {
                is UploadResult.Success -> {
                    broadcastLog("Manifest uploaded: ${result.url}")
                    true
                }
                is UploadResult.Failure -> {
                    broadcastLog("Manifest upload failed: ${result.error.message}")
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            broadcastLog("Manifest generation error: ${e.message}")
            false
        }
    }

}
