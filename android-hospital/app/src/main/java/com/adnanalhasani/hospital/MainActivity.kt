package com.adnanalhasani.hospital

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val database by lazy { HospitalDatabase.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<MaterialButton>(R.id.btnPatients).setOnClickListener {
            startActivity(Intent(this, PatientsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { database.patientDao().countPatients() }
            findViewById<TextView>(R.id.txtPatientCount).text = "المرضى المسجلون: $count"
        }
    }
}
