package com.adnanalhasani.hospital

import kotlinx.coroutines.flow.Flow

class PatientRepository(private val dao: PatientDao) {
    fun observePatients(): Flow<List<PatientEntity>> = dao.observePatients()
    suspend fun addPatient(name: String, phone: String): Long {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "اسم المريض مطلوب" }
        return dao.insertPatient(PatientEntity(fullName = cleanName, phone = phone.trim()))
    }
    suspend fun countPatients(): Int = dao.countPatients()
}
