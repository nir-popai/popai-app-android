package com.example.popai.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chunks")
data class ChunkEntity(
    @PrimaryKey
    val id: String,
    val recordingId: String,
    val chunkIndex: Int,
    val localFilePath: String,
    val encryptedFilePath: String,
    val durationMs: Long,
    val fileSize: Long,
    val uploadStatus: UploadStatus,
    val s3Key: String?,
    val s3Url: String?,
    val uploadAttempts: Int,
    val lastError: String?
)

enum class UploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED
}
