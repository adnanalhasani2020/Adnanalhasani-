package com.adnanalhasani.hospital

import kotlinx.coroutines.flow.Flow

/**
 * Repository boundary for Compose screens.
 * The DAO transaction keeps module-indexed rows and legacy records synchronized.
 */
class HospitalRepository(private val dao: HospitalDao) {
    fun allRecords(): Flow<List<HospitalRecord>> = dao.allRecords()

    fun referenceItems(category: String): Flow<List<ReferenceCatalogEntity>> = dao.referenceItems(category)

    suspend fun seedReferenceDefaults(category: String, names: List<String>) {
        if (dao.referenceCount(category) > 0) return
        names.distinct().forEach { name ->
            dao.insertReference(ReferenceCatalogEntity(category = category, name = name.trim()))
        }
    }

    suspend fun addReference(category: String, name: String, description: String = ""): Boolean {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return false
        return dao.insertReference(ReferenceCatalogEntity(category = category, name = cleanName, description = description.trim())) != -1L
    }

    suspend fun updateReference(id: Long, name: String, description: String, active: Boolean): Boolean {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return false
        return dao.updateReference(id, cleanName, description.trim(), active, System.currentTimeMillis()) > 0
    }

    suspend fun deactivateReference(id: Long): Boolean =
        dao.deactivateReference(id, System.currentTimeMillis()) > 0

    fun businessProfile(): Flow<BusinessProfileEntity?> = dao.businessProfile()

    suspend fun saveBusinessProfile(profile: BusinessProfileEntity) =
        dao.saveBusinessProfile(profile.copy(id = 1, updatedAt = System.currentTimeMillis()))

    fun recordsForModule(category: String): Flow<List<ModuleRecordEntity>> =
        dao.moduleRecords(category)

    fun allModuleRecords(): Flow<List<ModuleRecordEntity>> = dao.allModuleRecords()

    fun emergencyCases(): Flow<List<EmergencyCaseEntity>> = dao.emergencyCases()
    fun icuStays(): Flow<List<IcuStayEntity>> = dao.icuStays()
    fun inventoryItems(): Flow<List<InventoryItemEntity>> = dao.inventoryItems()
    fun labOrders(): Flow<List<LabOrderEntity>> = dao.labOrders()
    fun radiologyOrders(): Flow<List<RadiologyOrderEntity>> = dao.radiologyOrders()
    fun maintenanceTickets(): Flow<List<MaintenanceTicketEntity>> = dao.maintenanceTickets()
    fun vaccinationRecords(): Flow<List<VaccinationRecordEntity>> = dao.vaccinationRecords()
    fun vaccinationsForChild(fileNumber: String): Flow<List<VaccinationRecordEntity>> = dao.vaccinationsForChild(fileNumber)
    fun upcomingVaccinations(today: String): Flow<List<VaccinationRecordEntity>> = dao.upcomingVaccinations(today)
    fun overdueVaccinations(today: String): Flow<List<VaccinationRecordEntity>> = dao.overdueVaccinations(today)

    suspend fun saveRecord(record: HospitalRecord) = dao.saveRecord(record)

    suspend fun updateRecord(id: Long, title: String, details: String) = dao.updateRecord(id, title, details)

    suspend fun updateEmergencyCase(id: Long, details: String, status: String) = dao.updateEmergencyCase(id, details, status)

    suspend fun deleteRecord(id: Long) = dao.delete(id)
}
