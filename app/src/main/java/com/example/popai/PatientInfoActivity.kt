package com.example.popai

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.popai.databinding.ActivityPatientInfoBinding

class PatientInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPatientInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startRecordingButton.setOnClickListener {
            val patientName = binding.patientNameInput.text.toString().trim()
            val providerName = binding.providerNameInput.text.toString().trim()
            val notes = binding.notesInput.text.toString().trim()

            if (patientName.isEmpty()) {
                binding.patientNameInput.error = "Patient name is required"
                return@setOnClickListener
            }

            if (providerName.isEmpty()) {
                binding.providerNameInput.error = "Healthcare provider is required"
                return@setOnClickListener
            }

            // Navigate to recording activity
            val intent = Intent(this, RecordingActivity::class.java).apply {
                putExtra("PATIENT_NAME", patientName)
                putExtra("PROVIDER_NAME", providerName)
                putExtra("NOTES", notes)
            }
            startActivity(intent)
        }
    }
}
