package com.adnanalhasani.hospital

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PatientEntity::class], version = 1, exportSchema = false)
abstract class HospitalDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    companion object {
        @Volatile private var INSTANCE: HospitalDatabase? = null
        fun getInstance(context: Context): HospitalDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, HospitalDatabase::class.java, "hospital-local.db")
                    .build().also { INSTANCE = it }
            }
    }
}
