package com.adnanalhasani.hospital

import kotlinx.coroutines.flow.Flow

/**
 * Repository boundary for Compose screens.
 * The DAO transaction keeps module-indexed rows and legacy records synchronized.
 */
class HospitalRepository(private val dao: HospitalDao) {
    fun allRecords(): Flow<List<HospitalRecord>> = dao.allRecords()

    fun recordsForModule(category: String): Flow<List<ModuleRecordEntity>> =
        dao.moduleRecords(category)

    fun allModuleRecords(): Flow<List<ModuleRecordEntity>> = dao.allModuleRecords()

    suspend fun saveRecord(record: HospitalRecord) = dao.saveRecord(record)

    suspend fun deleteRecord(id: Long) = dao.delete(id)
}
