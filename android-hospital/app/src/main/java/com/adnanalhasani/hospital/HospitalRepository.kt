package com.adnanalhasani.hospital

import kotlinx.coroutines.flow.Flow

/**
 * Repository boundary for Compose screens.
 * The DAO transaction keeps module-indexed rows and legacy records synchronized.
 */
class HospitalRepository(private val dao: HospitalDao) {
    fun allRecords(): Flow<List<HospitalRecord>> = dao.allRecords()

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

    suspend fun deleteRecord(id: Long) = dao.delete(id)
}
