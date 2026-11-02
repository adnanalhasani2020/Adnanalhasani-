package com.adnanalhasani.hospital

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository boundary for Compose screens.
 * The DAO transaction keeps module-indexed rows and legacy records synchronized.
 */
class HospitalRepository(private val dao: HospitalDao, database: HospitalDatabase? = null) {
    private val localWorkflows = database?.let(::LocalWorkflowStore)
    fun allRecords(): Flow<List<HospitalRecord>> = dao.allRecords()

    suspend fun localWorkflowRows(category: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) { localWorkflows?.rows(category).orEmpty() }

    suspend fun localAuditRows(): List<Pair<String, String>> = localWorkflowRows("audit")

    suspend fun localFinanceOutboxRows(): List<Pair<String, String>> = localWorkflowRows("finance_outbox")

    fun patients(): Flow<List<PatientEntity>> = dao.patients()

    fun doctors(): Flow<List<DoctorEntity>> = dao.doctors()

    fun recentAuditHistory(): Flow<List<RecordAuditLogEntity>> = dao.recentAuditHistory()

    fun clinicalVisits(): Flow<List<ClinicalVisitEntity>> = dao.clinicalVisits()

    fun prescriptionOrders(): Flow<List<PrescriptionOrderEntity>> = dao.prescriptionOrders()

    fun labOrders(): Flow<List<LabOrderEntity>> = dao.labOrders()

    fun radiologyOrders(): Flow<List<RadiologyOrderEntity>> = dao.radiologyOrders()

    fun auditHistory(recordId: Long): Flow<List<RecordAuditLogEntity>> = dao.auditHistory(recordId)

    fun referenceItems(category: String): Flow<List<ReferenceCatalogEntity>> = dao.referenceItems(category)

    suspend fun seedDefaultReferenceCatalogs() {
        val defaults = mapOf(
            "lab_tests" to listOf("صورة دم كاملة CBC", "سكر الدم", "الهيموغلوبين السكري HbA1c", "وظائف الكلى", "وظائف الكبد", "تحليل البول", "سرعة الترسيب ESR", "CRP", "دهون الدم", "هرمون الغدة الدرقية TSH"),
            "medications" to listOf("باراسيتامول", "إيبوبروفين", "أموكسيسيلين", "أوميبرازول", "ميتفورمين", "محلول ملحي 0.9%", "سيتريزين"),
            "diagnoses" to listOf("ارتفاع ضغط الدم", "داء السكري من النوع الثاني", "فقر الدم", "التهاب الجهاز التنفسي العلوي", "التهاب المسالك البولية", "الربو", "التهاب المعدة والأمعاء"),
            "specialties" to listOf("طب الأسرة", "الباطنية", "الجراحة العامة", "طب الأطفال", "النساء والولادة", "القلب", "العظام", "الأنف والأذن والحنجرة", "العيون", "الجلدية"),
            "departments" to listOf("الطوارئ", "العيادات الخارجية", "الباطنية", "الجراحة", "الأطفال", "النساء والولادة", "العناية المركزة", "المختبر", "الأشعة", "الصيدلية"),
            "blood_groups" to listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"),
            "payment_methods" to listOf("نقداً", "بطاقة", "تحويل بنكي", "تأمين"),
            "lab_status" to listOf("مطلوب", "قيد التنفيذ", "مكتمل", "ملغي"),
            "admission_status" to listOf("قيد التقييم", "منوّم", "خرج", "محول"),
            "vaccination_status" to listOf("تم التطعيم", "موعد قادم", "تأجيل", "تعذر"),
            "imaging_types" to listOf("أشعة سينية X-Ray", "موجات فوق صوتية", "أشعة مقطعية CT", "رنين مغناطيسي MRI")
        )
        defaults.forEach { (category, names) -> seedReferenceDefaults(category, names) }
    }

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
    fun maintenanceTickets(): Flow<List<MaintenanceTicketEntity>> = dao.maintenanceTickets()
    fun vaccinationRecords(): Flow<List<VaccinationRecordEntity>> = dao.vaccinationRecords()
    fun vaccinationsForChild(fileNumber: String): Flow<List<VaccinationRecordEntity>> = dao.vaccinationsForChild(fileNumber)
    fun upcomingVaccinations(today: String): Flow<List<VaccinationRecordEntity>> = dao.upcomingVaccinations(today)
    fun overdueVaccinations(today: String): Flow<List<VaccinationRecordEntity>> = dao.overdueVaccinations(today)

    suspend fun saveRecord(record: HospitalRecord) {
        withContext(Dispatchers.IO) { localWorkflows?.validate(record) }
        val sourceRecordId = dao.saveRecord(record)
        try {
            withContext(Dispatchers.IO) { localWorkflows?.sync(record, sourceRecordId) }
        } catch (failure: Throwable) {
            // Keep the searchable Room record and operational SQLite projection consistent.
            runCatching { dao.delete(sourceRecordId) }
            throw failure
        }
    }

    suspend fun saveClinicalVisit(record: HospitalRecord, patientId: Long, doctorId: Long) {
        withContext(Dispatchers.IO) { localWorkflows?.validate(record) }
        val sourceRecordId = dao.saveRecord(record, linkedPatientId = patientId, linkedDoctorId = doctorId)
        try {
            withContext(Dispatchers.IO) { localWorkflows?.sync(record, sourceRecordId) }
        } catch (failure: Throwable) {
            runCatching { dao.delete(sourceRecordId) }
            throw failure
        }
    }

    suspend fun updateRecord(id: Long, title: String, details: String) {
        val current = dao.recordById(id) ?: return
        val updated = current.copy(title = title, details = details)
        withContext(Dispatchers.IO) { localWorkflows?.validate(updated, id) }
        dao.updateRecord(id, title, details)
        try {
            withContext(Dispatchers.IO) { localWorkflows?.sync(updated, id) }
        } catch (failure: Throwable) {
            // Restore the generic record if its local workflow projection rejects the update.
            runCatching { dao.updateRecord(id, current.title, current.details) }
            throw failure
        }
    }

    suspend fun updateClinicalVisitRecord(id: Long, title: String, details: String, patientId: Long, doctorId: Long) {
        val current = dao.recordById(id) ?: return
        val currentVisit = dao.clinicalVisitBySourceRecordId(id)
        val updated = current.copy(title = title, details = details)
        withContext(Dispatchers.IO) { localWorkflows?.validate(updated, id) }
        dao.updateRecord(id, title, details, linkedPatientId = patientId, linkedDoctorId = doctorId)
        try {
            withContext(Dispatchers.IO) { localWorkflows?.sync(updated, id) }
        } catch (failure: Throwable) {
            runCatching {
                dao.updateRecord(
                    id, current.title, current.details,
                    linkedPatientId = currentVisit?.patientId,
                    linkedDoctorId = currentVisit?.doctorId
                )
            }
            throw failure
        }
    }

    suspend fun updateEmergencyCase(id: Long, details: String, status: String) = dao.updateEmergencyCase(id, details, status)

    suspend fun deleteRecord(id: Long) {
        val current = dao.recordById(id)
        if (current != null) {
            withContext(Dispatchers.IO) { localWorkflows?.validateDeletion(current) }
        }
        dao.delete(id)
        if (current != null) withContext(Dispatchers.IO) { localWorkflows?.auditDeletion(current) }
    }
}
