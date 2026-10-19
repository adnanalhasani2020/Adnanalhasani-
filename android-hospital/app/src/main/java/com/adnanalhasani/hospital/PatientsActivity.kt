package com.adnanalhasani.hospital

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PatientsActivity : AppCompatActivity() {
    private lateinit var repository: PatientRepository
    private lateinit var adapter: PatientAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patients)
        repository = PatientRepository(HospitalDatabase.getInstance(applicationContext).patientDao())
        adapter = PatientAdapter()
        findViewById<RecyclerView>(R.id.listPatients).apply {
            layoutManager = LinearLayoutManager(this@PatientsActivity)
            adapter = this@PatientsActivity.adapter
        }

        lifecycleScope.launch {
            repository.observePatients().collect { patients ->
                adapter.submitItems(patients)
                findViewById<TextView>(R.id.txtEmptyPatients).visibility =
                    if (patients.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        findViewById<MaterialButton>(R.id.btnSavePatient).setOnClickListener {
            val nameField = findViewById<TextInputEditText>(R.id.inputPatientName)
            val phoneField = findViewById<TextInputEditText>(R.id.inputPatientPhone)
            val name = nameField.text?.toString().orEmpty()
            val phone = phoneField.text?.toString().orEmpty()
            if (name.isBlank()) {
                nameField.error = "أدخل اسم المريض"
                return@setOnClickListener
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { repository.addPatient(name, phone) }
                    nameField.setText("")
                    phoneField.setText("")
                    Toast.makeText(this@PatientsActivity, "تم حفظ المريض على هذا الجهاز", Toast.LENGTH_SHORT).show()
                } catch (exception: Exception) {
                    Toast.makeText(this@PatientsActivity, "تعذر حفظ السجل: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
