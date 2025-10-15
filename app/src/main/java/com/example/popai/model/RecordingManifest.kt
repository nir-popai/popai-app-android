package com.example.popai.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manifest file containing all metadata about a recording session
 */
data class RecordingManifest(
    val recordingId: String,
    val recordingStartDate: String,
    val uploadDate: String,
    val metadata: RecordingMetadata,
    val chunks: List<ChunkInfo>,
    val summary: RecordingSummary
) {
    fun toJson(): String {
        val gson = GsonBuilder()
            .setPrettyPrinting()
            .create()
        return gson.toJson(this)
    }

    companion object {
        fun fromJson(json: String): RecordingManifest {
            return Gson().fromJson(json, RecordingManifest::class.java)
        }
    }
}

/**
 * Patient and caregiver information from the recording session
 */
data class RecordingMetadata(
    val patientName: String,
    val healthcareProfessional: String
)

/**
 * Information about each audio chunk in the recording timeline
 */
data class ChunkInfo(
    val chunkIndex: Int,
    val durationMs: Long,            // Duration of this chunk
    val s3Key: String,               // S3 path to the file
    val sizeBytes: Long,             // File size in bytes
    val startTimeMs: Long            // Time from start of recording
)

/**
 * Summary statistics for the recording
 */
data class RecordingSummary(
    val totalChunks: Int,
    val totalDurationMs: Long,
    val totalSizeBytes: Long,
    val uploadedChunks: Int,
    val failedChunks: Int
)

/**
 * Helper function to format timestamp
 */
fun formatTimestamp(timeMillis: Long): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    dateFormat.timeZone = TimeZone.getTimeZone("UTC")
    return dateFormat.format(Date(timeMillis))
}
