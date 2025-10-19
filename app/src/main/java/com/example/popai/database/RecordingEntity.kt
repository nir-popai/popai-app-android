package com.example.popai.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey
    val id: String,
    val patientName: String,
    val healthcareProfessional: String,
    val startTime: Long,
    val endTime: Long?,
    val totalDurationMs: Long,
    val pausedDurationMs: Long,
    val status: RecordingStatus,
    val chunkCount: Int,
    val uploadedChunks: Int,
    val failedChunks: Int,
    val errorMessage: String?,
    val totalBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val currentChunkProgress: Int = 0
)

enum class RecordingStatus {
    RECORDING,
    COMPLETED,
    UPLOADING,
    UPLOADED,
    FAILED,
    PARTIAL
}
