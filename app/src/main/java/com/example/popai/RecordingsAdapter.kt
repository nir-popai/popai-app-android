package com.example.popai

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.popai.database.RecordingEntity
import com.example.popai.database.RecordingStatus
import com.example.popai.databinding.ItemRecordingBinding
import java.text.SimpleDateFormat
import java.util.*

class RecordingsAdapter(
    private val onRetryClick: (RecordingEntity) -> Unit
) : ListAdapter<RecordingEntity, RecordingsAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRecordingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(recording: RecordingEntity) {
            // Format date
            val dateFormat = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.US)
            binding.recordingDate.text = dateFormat.format(Date(recording.startTime))

            // Format duration
            val duration = recording.totalDurationMs / 1000
            val hours = duration / 3600
            val minutes = (duration % 3600) / 60
            val seconds = duration % 60
            binding.recordingDuration.text = if (hours > 0) {
                "Duration: %d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "Duration: %02d:%02d".format(minutes, seconds)
            }

            // Chunk count
            binding.recordingChunks.text = "Chunks: ${recording.chunkCount}"

            // Status
            binding.recordingStatus.text = recording.status.name
            when (recording.status) {
                RecordingStatus.UPLOADED -> {
                    binding.recordingStatus.setTextColor(Color.parseColor("#10B981"))
                    binding.uploadProgressLayout.visibility = View.GONE
                    binding.errorMessage.visibility = View.GONE
                    binding.retryButton.visibility = View.GONE
                }
                RecordingStatus.UPLOADING -> {
                    binding.recordingStatus.setTextColor(Color.parseColor("#6366F1"))
                    binding.uploadProgressLayout.visibility = View.VISIBLE
                    binding.uploadProgress.text = "Uploaded: ${recording.uploadedChunks}/${recording.chunkCount}"
                    binding.failedChunks.visibility = if (recording.failedChunks > 0) View.VISIBLE else View.GONE
                    binding.failedChunks.text = "Failed: ${recording.failedChunks}"
                    binding.errorMessage.visibility = View.GONE
                    binding.retryButton.visibility = View.GONE
                }
                RecordingStatus.FAILED -> {
                    binding.recordingStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.uploadProgressLayout.visibility = View.VISIBLE
                    binding.uploadProgress.text = "Uploaded: ${recording.uploadedChunks}/${recording.chunkCount}"
                    binding.failedChunks.visibility = View.VISIBLE
                    binding.failedChunks.text = "Failed: ${recording.failedChunks}"
                    binding.errorMessage.visibility = if (recording.errorMessage != null) View.VISIBLE else View.GONE
                    binding.errorMessage.text = recording.errorMessage
                    binding.retryButton.visibility = View.VISIBLE
                }
                RecordingStatus.PARTIAL -> {
                    binding.recordingStatus.setTextColor(Color.parseColor("#F59E0B"))
                    binding.uploadProgressLayout.visibility = View.VISIBLE
                    binding.uploadProgress.text = "Uploaded: ${recording.uploadedChunks}/${recording.chunkCount}"
                    binding.failedChunks.visibility = View.VISIBLE
                    binding.failedChunks.text = "Failed: ${recording.failedChunks}"
                    binding.errorMessage.visibility = View.GONE
                    binding.retryButton.visibility = View.VISIBLE
                }
                RecordingStatus.COMPLETED -> {
                    binding.recordingStatus.setTextColor(Color.parseColor("#6366F1"))
                    binding.uploadProgressLayout.visibility = View.GONE
                    binding.errorMessage.visibility = View.GONE
                    binding.retryButton.visibility = View.GONE
                }
                RecordingStatus.RECORDING -> {
                    binding.recordingStatus.setTextColor(Color.parseColor("#EF4444"))
                    binding.uploadProgressLayout.visibility = View.GONE
                    binding.errorMessage.visibility = View.GONE
                    binding.retryButton.visibility = View.GONE
                }
            }

            // Retry button
            binding.retryButton.setOnClickListener {
                onRetryClick(recording)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RecordingEntity>() {
        override fun areItemsTheSame(oldItem: RecordingEntity, newItem: RecordingEntity) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: RecordingEntity, newItem: RecordingEntity) =
            oldItem == newItem
    }
}
