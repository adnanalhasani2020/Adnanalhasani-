package com.adnanalhasani.hospital

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients ORDER BY createdAt DESC, id DESC")
    fun observePatients(): Flow<List<PatientEntity>>
    @Insert
    suspend fun insertPatient(patient: PatientEntity): Long
    @Query("SELECT COUNT(*) FROM patients")
    suspend fun countPatients(): Int
}
