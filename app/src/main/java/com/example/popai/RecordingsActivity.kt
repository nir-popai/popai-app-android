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
            val uploadService = UploadService()
            uploadService.retryFailedUploads(recording.id)

            val intent = Intent(this@RecordingsActivity, UploadService::class.java).apply {
                putExtra("recording_id", recording.id)
            }
            startService(intent)
        }
    }
}
