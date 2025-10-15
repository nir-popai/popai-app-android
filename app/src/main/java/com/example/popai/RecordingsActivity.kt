package com.example.popai

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.popai.database.AppDatabase
import com.example.popai.database.RecordingEntity
import com.example.popai.databinding.ActivityRecordingsBinding
import com.example.popai.service.UploadService
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var database: AppDatabase
    private lateinit var adapter: RecordingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(applicationContext)

        setupToolbar()
        setupRecyclerView()
        observeRecordings()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = RecordingsAdapter(
            onRetryClick = { recording ->
                retryUpload(recording)
            }
        )

        binding.recordingsList.layoutManager = LinearLayoutManager(this)
        binding.recordingsList.adapter = adapter
    }

    private fun observeRecordings() {
        lifecycleScope.launch {
            database.recordingDao().getAllRecordings().collect { recordings ->
                if (recordings.isEmpty()) {
                    binding.emptyState.visibility = View.VISIBLE
                    binding.recordingsList.visibility = View.GONE
                } else {
                    binding.emptyState.visibility = View.GONE
                    binding.recordingsList.visibility = View.VISIBLE
                    adapter.submitList(recordings)
                }
            }
        }
    }

    private fun retryUpload(recording: RecordingEntity) {
        lifecycleScope.launch {
            // Debug: Log all chunks for this recording
            val allChunks = database.chunkDao().getChunksForRecordingSync(recording.id)
            android.util.Log.d("RecordingsActivity", "=== Chunks for recording ${recording.id} ===")
            allChunks.forEach { chunk ->
                android.util.Log.d("RecordingsActivity",
                    "Chunk ${chunk.chunkIndex}: " +
                    "status=${chunk.uploadStatus}, " +
                    "fileSize=${chunk.fileSize}, " +
                    "duration=${chunk.durationMs}, " +
                    "s3Key=${chunk.s3Key}, " +
                    "error=${chunk.lastError}, " +
                    "path=${chunk.localFilePath}")
            }
            android.util.Log.d("RecordingsActivity", "=== End chunks ===")

            // Reset failed chunks to pending status
            val failedChunks = database.chunkDao().getChunksByRecordingAndStatus(
                recording.id,
                com.example.popai.database.UploadStatus.FAILED
            )

            failedChunks.forEach { chunk ->
                database.chunkDao().updateChunk(
                    chunk.copy(
                        uploadStatus = com.example.popai.database.UploadStatus.PENDING,
                        lastError = null
                    )
                )
            }

            // Also reset PENDING chunks that might have been left in limbo
            val pendingChunks = database.chunkDao().getChunksByRecordingAndStatus(
                recording.id,
                com.example.popai.database.UploadStatus.PENDING
            )

            // Update recording status back to UPLOADING
            database.recordingDao().updateRecording(
                recording.copy(
                    status = com.example.popai.database.RecordingStatus.UPLOADING,
                    errorMessage = null
                )
            )

            // Start upload service
            val intent = Intent(this@RecordingsActivity, UploadService::class.java).apply {
                putExtra("recording_id", recording.id)
            }
            startService(intent)
        }
    }
}
