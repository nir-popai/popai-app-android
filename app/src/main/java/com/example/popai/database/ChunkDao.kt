package com.example.popai.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: ChunkEntity)

    @Update
    suspend fun updateChunk(chunk: ChunkEntity)

    @Query("SELECT * FROM chunks WHERE recordingId = :recordingId ORDER BY chunkIndex")
    fun getChunksForRecording(recordingId: String): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE recordingId = :recordingId ORDER BY chunkIndex")
    suspend fun getChunksForRecordingSync(recordingId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE uploadStatus = :status")
    suspend fun getChunksByStatus(status: UploadStatus): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE recordingId = :recordingId AND uploadStatus = :status")
    suspend fun getChunksByRecordingAndStatus(recordingId: String, status: UploadStatus): List<ChunkEntity>

    @Delete
    suspend fun deleteChunk(chunk: ChunkEntity)

    @Query("DELETE FROM chunks WHERE recordingId = :recordingId")
    suspend fun deleteChunksForRecording(recordingId: String)
}
