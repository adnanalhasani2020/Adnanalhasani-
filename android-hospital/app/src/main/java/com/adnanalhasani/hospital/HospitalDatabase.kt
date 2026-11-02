package com.adnanalhasani.hospital

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Legacy-compatible general record table. Kept so existing v1 data is preserved.
 */
@Entity(
    tableName = "hospital_records",
    indices = [Index(value = ["category", "createdAt"]), Index(value = ["title"])]
)
data class HospitalRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val title: String,
    val details: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Category-indexed module record. sourceRecordId keeps it linked to the legacy row.
 */
@Entity(
    tableName = "module_records",
    indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["category", "createdAt"])]
)
data class ModuleRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long,
    val category: String,
    val title: String,
    val details: String,
    val createdAt: Long
)

@Entity(tableName = "emergency_cases", foreignKeys = [ForeignKey(entity = HospitalRecord::class, parentColumns = ["id"], childColumns = ["sourceRecordId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["patientId"]), Index(value = ["triageLevel", "createdAt"])])
data class EmergencyCaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val caseNumber: String, val patientName: String,
    val arrivalTime: String, val triageLevel: String, val chiefComplaint: String,
    val status: String, val createdAt: Long,
    val patientId: Long? = null
)

@Entity(tableName = "icu_stays", foreignKeys = [ForeignKey(entity = HospitalRecord::class, parentColumns = ["id"], childColumns = ["sourceRecordId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.SET_NULL), ForeignKey(entity = DoctorEntity::class, parentColumns = ["id"], childColumns = ["doctorId"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["patientId"]), Index(value = ["doctorId"]), Index(value = ["bedNumber"])])
data class IcuStayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val patientName: String, val bedNumber: String,
    val attendingDoctor: String, val careLevel: String, val admissionDate: String,
    val teamNotes: String, val createdAt: Long,
    val patientId: Long? = null,
    val doctorId: Long? = null
)

@Entity(tableName = "inventory_items", indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["sku"])])
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val name: String, val sku: String,
    val quantity: Double, val reorderLevel: Double, val unit: String,
    val notes: String, val createdAt: Long
)

@Entity(tableName = "lab_orders", foreignKeys = [ForeignKey(entity = HospitalRecord::class, parentColumns = ["id"], childColumns = ["sourceRecordId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["patientId"]), Index(value = ["clinicalVisitId"]), Index(value = ["status", "createdAt"])])
data class LabOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val patientName: String, val testName: String,
    val status: String, val result: String, val createdAt: Long,
    val patientId: Long? = null,
    val clinicalVisitId: Long? = null
)

@Entity(tableName = "radiology_orders", foreignKeys = [ForeignKey(entity = HospitalRecord::class, parentColumns = ["id"], childColumns = ["sourceRecordId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.SET_NULL), ForeignKey(entity = DoctorEntity::class, parentColumns = ["id"], childColumns = ["doctorId"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["patientId"]), Index(value = ["doctorId"]), Index(value = ["clinicalVisitId"]), Index(value = ["status", "createdAt"])])
data class RadiologyOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val patientName: String, val imagingType: String,
    val requestingDoctor: String, val status: String, val report: String, val createdAt: Long,
    val patientId: Long? = null,
    val doctorId: Long? = null,
    val clinicalVisitId: Long? = null
)

@Entity(
    tableName = "prescription_orders",
    foreignKeys = [
        ForeignKey(entity = HospitalRecord::class, parentColumns = ["id"], childColumns = ["sourceRecordId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = DoctorEntity::class, parentColumns = ["id"], childColumns = ["doctorId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = ClinicalVisitEntity::class, parentColumns = ["id"], childColumns = ["clinicalVisitId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["patientId"]), Index(value = ["doctorId"]), Index(value = ["clinicalVisitId"]), Index(value = ["status", "createdAt"])]
)
data class PrescriptionOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long,
    val patientId: Long? = null,
    val doctorId: Long? = null,
    val clinicalVisitId: Long? = null,
    val patientName: String,
    val doctorName: String,
    val medicationName: String,
    val dose: String,
    val frequencyAndDuration: String,
    val status: String,
    val instructions: String,
    val createdAt: Long
)

@Entity(tableName = "maintenance_tickets", indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["status", "createdAt"])])
data class MaintenanceTicketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val ticketNumber: String, val reporter: String,
    val location: String, val issue: String, val priority: String, val status: String,
    val createdAt: Long
)

@Entity(
    tableName = "vaccination_records",
    indices = [
        Index(value = ["sourceRecordId"], unique = true),
        Index(value = ["childFileNumber"]),
        Index(value = ["nextDoseDate"]),
        Index(value = ["childName", "vaccineName"])
    ]
)
data class VaccinationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long,
    val childName: String,
    val childFileNumber: String = "",
    val dateOfBirth: String = "",
    val vaccineName: String,
    val doseNumber: String,
    val administeredDate: String,
    val nextDoseDate: String = "",
    val batchNumber: String = "",
    val administrationSite: String = "",
    val administeredBy: String = "",
    val doseStatus: String = "تم التطعيم",
    val notes: String = "",
    val createdAt: Long
)

@Entity(tableName = "business_profile")
data class BusinessProfileEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val legalName: String = "",
    val businessType: String = "",
    val registrationNumber: String = "",
    val taxNumber: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val website: String = "",
    val currency: String = "YER",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "patients", indices = [Index(value = ["fileNumber"], unique = true)])
data class PatientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileNumber: String,
    val fullName: String,
    val phone: String = "",
    val age: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "doctors", indices = [Index(value = ["employeeNumber"], unique = true)])
data class DoctorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val employeeNumber: String,
    val fullName: String,
    val specialty: String = "",
    val phone: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "appointments",
    foreignKeys = [
        ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = DoctorEntity::class, parentColumns = ["id"], childColumns = ["doctorId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [
        Index(value = ["patientId"]), Index(value = ["doctorId"]),
        Index(value = ["patientName"]), Index(value = ["doctorName"]), Index(value = ["appointmentAt"])
    ]
)
data class AppointmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientName: String,
    val doctorName: String,
    val patientId: Long? = null,
    val doctorId: Long? = null,
    val appointmentAt: String = "",
    val reason: String = "",
    val status: String = "scheduled",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "invoices",
    foreignKeys = [ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["invoiceNumber"], unique = true), Index(value = ["patientId"])]
)
data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNumber: String,
    val patientName: String = "",
    val patientId: Long? = null,
    val amountMinor: Long = 0,
    val currency: String = "YER",
    val status: String = "open",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(entity = InvoiceEntity::class, parentColumns = ["id"], childColumns = ["invoiceId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["invoiceNumber"]), Index(value = ["invoiceId"]), Index(value = ["createdAt"])]
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNumber: String,
    val invoiceId: Long? = null,
    val amountMinor: Long = 0,
    val method: String = "",
    val receiptReference: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "reference_catalog",
    indices = [Index(value = ["category", "name"], unique = true), Index(value = ["category", "active"])]
)
data class ReferenceCatalogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val name: String,
    val description: String = "",
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "clinical_visits",
    foreignKeys = [
        ForeignKey(entity = HospitalRecord::class, parentColumns = ["id"], childColumns = ["sourceRecordId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PatientEntity::class, parentColumns = ["id"], childColumns = ["patientId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = DoctorEntity::class, parentColumns = ["id"], childColumns = ["doctorId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [
        Index(value = ["sourceRecordId"], unique = true),
        Index(value = ["patientId"]),
        Index(value = ["doctorId"]),
        Index(value = ["visitAt"])
    ]
)
data class ClinicalVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long,
    val patientId: Long? = null,
    val doctorId: Long? = null,
    val patientName: String,
    val doctorName: String,
    val chiefComplaint: String,
    val diagnosis: String,
    val notes: String,
    val visitAt: String,
    val createdAt: Long
)

@Entity(tableName = "record_audit_log", indices = [Index(value = ["recordId", "changedAt"]), Index(value = ["category", "changedAt"])])
data class RecordAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val category: String,
    val action: String,
    val oldTitle: String = "",
    val newTitle: String = "",
    val oldDetails: String = "",
    val newDetails: String = "",
    val changedAt: Long = System.currentTimeMillis()
)

@Dao
interface HospitalDao {
    @Query("SELECT * FROM reference_catalog WHERE category = :category ORDER BY active DESC, name COLLATE NOCASE")
    fun referenceItems(category: String): Flow<List<ReferenceCatalogEntity>>

    @Query("SELECT COUNT(*) FROM reference_catalog WHERE category = :category")
    suspend fun referenceCount(category: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReferenceRow(item: ReferenceCatalogEntity): Long

    @Query("SELECT * FROM reference_catalog WHERE id = :id LIMIT 1")
    suspend fun referenceById(id: Long): ReferenceCatalogEntity?

    @Query("UPDATE reference_catalog SET name = :name, description = :description, active = :active, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateReferenceRow(id: Long, name: String, description: String, active: Boolean, updatedAt: Long): Int

    @Query("UPDATE reference_catalog SET active = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun deactivateReferenceRow(id: Long, updatedAt: Long): Int

    @Transaction
    suspend fun insertReference(item: ReferenceCatalogEntity): Long {
        val id = insertReferenceRow(item)
        if (id > 0L) {
            insertAuditLog(RecordAuditLogEntity(
                recordId = id, category = "reference_catalog:${item.category}", action = "CREATE",
                newTitle = item.name, newDetails = item.description, changedAt = System.currentTimeMillis()
            ))
        }
        return id
    }

    @Transaction
    suspend fun updateReference(id: Long, name: String, description: String, active: Boolean, updatedAt: Long): Int {
        val old = referenceById(id) ?: return 0
        val changed = updateReferenceRow(id, name, description, active, updatedAt)
        if (changed > 0) {
            insertAuditLog(RecordAuditLogEntity(
                recordId = id, category = "reference_catalog:${old.category}", action = "UPDATE",
                oldTitle = old.name, newTitle = name, oldDetails = old.description,
                newDetails = description + if (active) "" else "\nالحالة: معطّل",
                changedAt = updatedAt
            ))
        }
        return changed
    }

    @Transaction
    suspend fun deactivateReference(id: Long, updatedAt: Long): Int {
        val old = referenceById(id) ?: return 0
        val changed = deactivateReferenceRow(id, updatedAt)
        if (changed > 0) {
            insertAuditLog(RecordAuditLogEntity(
                recordId = id, category = "reference_catalog:${old.category}", action = "DEACTIVATE",
                oldTitle = old.name, newTitle = old.name, oldDetails = old.description,
                newDetails = old.description + "\nالحالة: معطّل", changedAt = updatedAt
            ))
        }
        return changed
    }

    @Query("SELECT * FROM hospital_records WHERE category = :category ORDER BY createdAt DESC")
    fun records(category: String): Flow<List<HospitalRecord>>

    @Query("SELECT * FROM hospital_records ORDER BY createdAt DESC")
    fun allRecords(): Flow<List<HospitalRecord>>

    @Insert
    suspend fun insert(record: HospitalRecord): Long

    @Insert
    suspend fun insertModuleRecord(record: ModuleRecordEntity)

    @Query("SELECT * FROM module_records WHERE category = :category ORDER BY createdAt DESC")
    fun moduleRecords(category: String): Flow<List<ModuleRecordEntity>>

    @Query("SELECT * FROM module_records ORDER BY createdAt DESC")
    fun allModuleRecords(): Flow<List<ModuleRecordEntity>>

    @Query("DELETE FROM module_records WHERE sourceRecordId = :sourceRecordId")
    suspend fun deleteModuleRecord(sourceRecordId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBusinessProfile(profile: BusinessProfileEntity)

    @Query("SELECT * FROM business_profile WHERE id = 1 LIMIT 1")
    fun businessProfile(): Flow<BusinessProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveEmergencyCase(entity: EmergencyCaseEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveIcuStay(entity: IcuStayEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveInventoryItem(entity: InventoryItemEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveLabOrder(entity: LabOrderEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRadiologyOrder(entity: RadiologyOrderEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMaintenanceTicket(entity: MaintenanceTicketEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveVaccinationRecord(entity: VaccinationRecordEntity)

    @Query("SELECT * FROM emergency_cases ORDER BY createdAt DESC") fun emergencyCases(): Flow<List<EmergencyCaseEntity>>
    @Query("SELECT * FROM icu_stays ORDER BY createdAt DESC") fun icuStays(): Flow<List<IcuStayEntity>>
    @Query("SELECT * FROM inventory_items ORDER BY name COLLATE NOCASE") fun inventoryItems(): Flow<List<InventoryItemEntity>>
    @Query("SELECT * FROM lab_orders ORDER BY createdAt DESC") fun labOrders(): Flow<List<LabOrderEntity>>
    @Query("SELECT * FROM radiology_orders ORDER BY createdAt DESC") fun radiologyOrders(): Flow<List<RadiologyOrderEntity>>
    @Query("SELECT * FROM maintenance_tickets ORDER BY createdAt DESC") fun maintenanceTickets(): Flow<List<MaintenanceTicketEntity>>
    @Query("SELECT * FROM vaccination_records ORDER BY nextDoseDate ASC, administeredDate DESC") fun vaccinationRecords(): Flow<List<VaccinationRecordEntity>>
    @Query("SELECT * FROM vaccination_records WHERE childFileNumber = :fileNumber ORDER BY administeredDate DESC") fun vaccinationsForChild(fileNumber: String): Flow<List<VaccinationRecordEntity>>
    @Query("SELECT * FROM vaccination_records WHERE nextDoseDate != '' AND nextDoseDate >= :today ORDER BY nextDoseDate ASC") fun upcomingVaccinations(today: String): Flow<List<VaccinationRecordEntity>>
    @Query("SELECT * FROM vaccination_records WHERE nextDoseDate != '' AND nextDoseDate < :today ORDER BY nextDoseDate ASC") fun overdueVaccinations(today: String): Flow<List<VaccinationRecordEntity>>

    @Query("UPDATE hospital_records SET details = :details WHERE id = :id") suspend fun updateHospitalRecordDetails(id: Long, details: String)
    @Query("UPDATE module_records SET details = :details WHERE sourceRecordId = :id") suspend fun updateModuleRecordDetails(id: Long, details: String)
    @Query("UPDATE emergency_cases SET status = :status WHERE sourceRecordId = :id") suspend fun updateEmergencyCaseStatus(id: Long, status: String)
    @Query("UPDATE hospital_records SET title = :title, details = :details WHERE id = :id")
    suspend fun updateRecordRow(id: Long, title: String, details: String)
    @Query("UPDATE module_records SET title = :title, details = :details WHERE sourceRecordId = :id")
    suspend fun updateModuleRecordRow(id: Long, title: String, details: String)

    @Query("UPDATE patients SET fileNumber = :newFileNumber, fullName = :fullName, phone = :phone, age = :age, notes = :notes WHERE fileNumber = :oldFileNumber")
    suspend fun updatePatientRecord(oldFileNumber: String, newFileNumber: String, fullName: String, phone: String, age: String, notes: String): Int

    @Query("UPDATE doctors SET employeeNumber = :newEmployeeNumber, fullName = :fullName, specialty = :specialty, phone = :phone WHERE employeeNumber = :oldEmployeeNumber")
    suspend fun updateDoctorRecord(oldEmployeeNumber: String, newEmployeeNumber: String, fullName: String, specialty: String, phone: String): Int

    @Query("UPDATE appointments SET patientName = :patientName, doctorName = :doctorName, patientId = :patientId, doctorId = :doctorId, appointmentAt = :appointmentAt, reason = :reason WHERE patientName = :oldPatientName AND createdAt = :createdAt")
    suspend fun updateAppointmentRecord(oldPatientName: String, createdAt: Long, patientName: String, doctorName: String, patientId: Long?, doctorId: Long?, appointmentAt: String, reason: String): Int

    @Query("UPDATE invoices SET invoiceNumber = :invoiceNumber, patientName = :patientName, patientId = :patientId, amountMinor = :amountMinor, currency = :currency, status = :status WHERE invoiceNumber = :oldInvoiceNumber")
    suspend fun updateInvoiceRecord(oldInvoiceNumber: String, invoiceNumber: String, patientName: String, patientId: Long?, amountMinor: Long, currency: String, status: String): Int

    @Query("UPDATE payments SET invoiceNumber = :invoiceNumber, invoiceId = :invoiceId, amountMinor = :amountMinor, method = :method, receiptReference = :receiptReference WHERE invoiceNumber = :oldInvoiceNumber AND createdAt = :createdAt")
    suspend fun updatePaymentRecord(oldInvoiceNumber: String, createdAt: Long, invoiceNumber: String, invoiceId: Long?, amountMinor: Long, method: String, receiptReference: String): Int
    @Query("""UPDATE vaccination_records SET childName = :childName, childFileNumber = :fileNumber,
        dateOfBirth = :dateOfBirth, vaccineName = :vaccineName, doseNumber = :doseNumber,
        administeredDate = :administeredDate, nextDoseDate = :nextDoseDate, batchNumber = :batchNumber,
        administrationSite = :site, administeredBy = :administeredBy, doseStatus = :doseStatus,
        notes = :notes WHERE sourceRecordId = :id""")
    suspend fun updateVaccinationRow(id: Long, childName: String, fileNumber: String, dateOfBirth: String,
        vaccineName: String, doseNumber: String, administeredDate: String, nextDoseDate: String,
        batchNumber: String, site: String, administeredBy: String, doseStatus: String, notes: String)
    @Query("""UPDATE emergency_cases SET caseNumber = :caseNumber, patientName = :patientName, patientId = :patientId,
        arrivalTime = :arrivalTime, triageLevel = :triageLevel, chiefComplaint = :chiefComplaint,
        status = :status WHERE sourceRecordId = :id""")
    suspend fun updateEmergencyCaseRow(id: Long, caseNumber: String, patientName: String, patientId: Long?, arrivalTime: String,
        triageLevel: String, chiefComplaint: String, status: String)

    @Query("""UPDATE icu_stays SET patientName = :patientName, patientId = :patientId, bedNumber = :bedNumber,
        attendingDoctor = :attendingDoctor, doctorId = :doctorId, careLevel = :careLevel, admissionDate = :admissionDate,
        teamNotes = :teamNotes WHERE sourceRecordId = :id""")
    suspend fun updateIcuStayRow(id: Long, patientName: String, patientId: Long?, bedNumber: String, attendingDoctor: String,
        doctorId: Long?, careLevel: String, admissionDate: String, teamNotes: String)

    @Query("""UPDATE inventory_items SET name = :name, sku = :sku, quantity = :quantity,
        reorderLevel = :reorderLevel, unit = :unit, notes = :notes WHERE sourceRecordId = :id""")
    suspend fun updateInventoryItemRow(id: Long, name: String, sku: String, quantity: Double,
        reorderLevel: Double, unit: String, notes: String)

    @Query("""UPDATE lab_orders SET patientName = :patientName, patientId = :patientId, testName = :testName,
        status = :status, result = :result WHERE sourceRecordId = :id""")
    suspend fun updateLabOrderRow(id: Long, patientName: String, patientId: Long?, testName: String, status: String, result: String)

    @Query("""UPDATE radiology_orders SET patientName = :patientName, patientId = :patientId, imagingType = :imagingType,
        requestingDoctor = :requestingDoctor, doctorId = :doctorId, status = :status, report = :report WHERE sourceRecordId = :id""")
    suspend fun updateRadiologyOrderRow(id: Long, patientName: String, patientId: Long?, imagingType: String,
        requestingDoctor: String, doctorId: Long?, status: String, report: String)

    @Query("UPDATE lab_orders SET clinicalVisitId = :clinicalVisitId WHERE sourceRecordId = :id")
    suspend fun updateLabClinicalVisit(id: Long, clinicalVisitId: Long?)

    @Query("UPDATE radiology_orders SET clinicalVisitId = :clinicalVisitId WHERE sourceRecordId = :id")
    suspend fun updateRadiologyClinicalVisit(id: Long, clinicalVisitId: Long?)

    @Query("""UPDATE prescription_orders SET patientName = :patientName, patientId = :patientId, doctorName = :doctorName,
        doctorId = :doctorId, medicationName = :medicationName, dose = :dose, frequencyAndDuration = :frequencyAndDuration,
        status = :status, instructions = :instructions, clinicalVisitId = :clinicalVisitId WHERE sourceRecordId = :id""")
    suspend fun updatePrescriptionOrderRow(id: Long, patientName: String, patientId: Long?, doctorName: String,
        doctorId: Long?, medicationName: String, dose: String, frequencyAndDuration: String, status: String,
        instructions: String, clinicalVisitId: Long?)

    @Query("""UPDATE maintenance_tickets SET ticketNumber = :ticketNumber, reporter = :reporter,
        location = :location, issue = :issue, priority = :priority, status = :status WHERE sourceRecordId = :id""")
    suspend fun updateMaintenanceTicketRow(id: Long, ticketNumber: String, reporter: String, location: String,
        issue: String, priority: String, status: String)

    @Transaction
    suspend fun updateRecord(id: Long, title: String, details: String, linkedPatientId: Long? = null, linkedDoctorId: Long? = null) {
        val current = recordById(id) ?: return
        insertAuditLog(RecordAuditLogEntity(recordId = id, category = current.category, action = "UPDATE", oldTitle = current.title, newTitle = title, oldDetails = current.details, newDetails = details, changedAt = System.currentTimeMillis()))
        val fields = details.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 2).trim()
        }.toMap()
        fun field(vararg names: String): String = names.firstNotNullOfOrNull { fields[it]?.takeIf(String::isNotBlank) }.orEmpty()
        updateRecordRow(id, title, details)
        updateModuleRecordRow(id, title, details)
        when (current.category) {
            "encounters" -> {
                val updated = updateClinicalVisit(id, linkedPatientId ?: findPatientIdByName(field("اسم المريض")), linkedDoctorId ?: findDoctorIdByName(field("اسم الطبيب")),
                    field("اسم المريض"), field("اسم الطبيب"), field("الشكوى الرئيسية", "الشكوى"),
                    field("التشخيص"), field("ملاحظات", "التشخيص والملاحظات"), field("تاريخ الزيارة", "التاريخ والوقت"))
                if (updated == 0) saveClinicalVisit(ClinicalVisitEntity(sourceRecordId = id,
                    patientId = linkedPatientId ?: findPatientIdByName(field("اسم المريض")), doctorId = linkedDoctorId ?: findDoctorIdByName(field("اسم الطبيب")),
                    patientName = field("اسم المريض"), doctorName = field("اسم الطبيب"),
                    chiefComplaint = field("الشكوى الرئيسية", "الشكوى"), diagnosis = field("التشخيص"),
                    notes = field("ملاحظات", "التشخيص والملاحظات"), visitAt = field("تاريخ الزيارة", "التاريخ والوقت"),
                    createdAt = current.createdAt))
            }
            "emergency" -> updateEmergencyCaseRow(id, title, field("اسم المريض"), findPatientIdByName(field("اسم المريض")), field("وقت الوصول"),
                field("درجة الفرز"), field("الشكوى الرئيسية"), field("حالة الحالة"))
            "vaccination" -> updateVaccinationRow(id, field("اسم الطفل", "اسم المريض").ifBlank { title },
                field("رقم ملف الطفل", "رقم الملف"), field("تاريخ الميلاد"), field("اسم اللقاح"),
                field("رقم الجرعة"), field("تاريخ التطعيم"), field("موعد الجرعة التالية"),
                field("رقم التشغيلة"), field("مكان التطعيم"), field("الممارس الصحي"),
                field("حالة الجرعة").ifBlank { "تم التطعيم" }, field("ملاحظات"))
            "icu" -> updateIcuStayRow(id, field("اسم المريض"), findPatientIdByName(field("اسم المريض")), field("رقم السرير"),
                field("الطبيب المسؤول"), findDoctorIdByName(field("الطبيب المسؤول")), field("مستوى الرعاية"), field("تاريخ الدخول"), field("ملاحظات الفريق"))
            "inventory" -> updateInventoryItemRow(id, field("اسم الصنف").ifBlank { title },
                field("رمز الصنف").ifBlank { "ITEM-" + id },
                field("الكمية الحالية").toDoubleOrNull() ?: 0.0,
                field("حد إعادة الطلب").toDoubleOrNull() ?: 0.0,
                field("وحدة القياس"), field("ملاحظات"))
            "lab" -> {
                updateLabOrderRow(id, field("اسم المريض"), findPatientIdByName(field("اسم المريض")), field("اسم الفحص"), field("حالة الفحص"), field("النتيجة"))
                updateLabClinicalVisit(id, field("معرّف الزيارة").toLongOrNull()?.let { existingClinicalVisitId(it) })
            }
            "radiology" -> {
                updateRadiologyOrderRow(id, field("اسم المريض"), findPatientIdByName(field("اسم المريض")), field("نوع التصوير"), field("الطبيب الطالب"), findDoctorIdByName(field("الطبيب الطالب")), field("الحالة"), field("التقرير"))
                updateRadiologyClinicalVisit(id, field("معرّف الزيارة").toLongOrNull()?.let { existingClinicalVisitId(it) })
            }
            "prescriptions" -> updatePrescriptionOrderRow(id, field("اسم المريض"), findPatientIdByName(field("اسم المريض")), field("اسم الطبيب"), findDoctorIdByName(field("اسم الطبيب")), field("اسم الدواء"), field("الجرعة"), field("التكرار والمدة"), field("الحالة"), field("تعليمات إضافية", "ملاحظات"), field("معرّف الزيارة").toLongOrNull()?.let { existingClinicalVisitId(it) })
            "maintenance" -> updateMaintenanceTicketRow(id, title, field("مقدم البلاغ"),
                field("الموقع"), field("نوع العطل"), field("الأولوية"), field("الحالة"))
            "patients" -> {
                val updated = updatePatientRecord(current.title, title,
                    field("اسم المريض").ifBlank { title }, field("رقم الهاتف"),
                    field("العمر"), field("ملاحظات"))
                if (updated == 0) upsertPatient(PatientEntity(
                    fileNumber = title,
                    fullName = field("اسم المريض").ifBlank { title },
                    phone = field("رقم الهاتف"), age = field("العمر"),
                    notes = field("ملاحظات"), createdAt = current.createdAt
                ))
            }
            "doctors" -> {
                val updated = updateDoctorRecord(current.title, title,
                    field("اسم الطبيب").ifBlank { title }, field("التخصص"), field("رقم الهاتف"))
                if (updated == 0) upsertDoctor(DoctorEntity(
                    employeeNumber = title,
                    fullName = field("اسم الطبيب").ifBlank { title },
                    specialty = field("التخصص"), phone = field("رقم الهاتف"),
                    createdAt = current.createdAt
                ))
            }
            "appointments" -> updateAppointmentRecord(
                oldPatientName = current.title, createdAt = current.createdAt,
                patientName = field("اسم المريض"), doctorName = field("اسم الطبيب"),
                patientId = findPatientIdByName(field("اسم المريض")),
                doctorId = findDoctorIdByName(field("اسم الطبيب")),
                appointmentAt = field("التاريخ والوقت"), reason = field("سبب الزيارة")
            )
            "invoices" -> {
                val amountMinor = requireNotNull(
                    HospitalFieldValidation.toMinorUnitsOrNull(field("المبلغ"))
                ) { "Invalid invoice amount" }
                val updated = updateInvoiceRecord(
                    oldInvoiceNumber = current.title, invoiceNumber = title,
                    patientName = field("اسم المريض"),
                    patientId = findPatientIdByName(field("اسم المريض")),
                    amountMinor = amountMinor,
                    currency = field("العملة").ifBlank { "YER" },
                    status = field("الحالة").ifBlank { "open" }
                )
                if (updated == 0) upsertInvoice(InvoiceEntity(
                    invoiceNumber = title, patientName = field("اسم المريض"),
                    patientId = findPatientIdByName(field("اسم المريض")),
                    amountMinor = amountMinor, currency = field("العملة").ifBlank { "YER" },
                    status = field("الحالة").ifBlank { "open" }, createdAt = current.createdAt
                ))
            }
            "payments" -> {
                val invoiceNumber = field("رقم الفاتورة").ifBlank { title }
                val amountMinor = requireNotNull(
                    HospitalFieldValidation.toMinorUnitsOrNull(field("المبلغ المدفوع"))
                ) { "Invalid payment amount" }
                updatePaymentRecord(
                    oldInvoiceNumber = current.title, createdAt = current.createdAt,
                    invoiceNumber = invoiceNumber,
                    invoiceId = findInvoiceIdByNumber(invoiceNumber),
                    amountMinor = amountMinor, method = field("طريقة الدفع"),
                    receiptReference = field("مرجع الإيصال")
                )
            }
        }
    }

    @Transaction
    suspend fun updateEmergencyCase(id: Long, details: String, status: String) {
        val current = recordById(id) ?: return
        val timestamp = System.currentTimeMillis()
        val nextDetails = details.lineSequence().filterNot { it.startsWith("حالة الحالة:") }
            .toList().plus("حالة الحالة: $status").joinToString("\n")
        insertAuditLog(
            RecordAuditLogEntity(
                recordId = id,
                category = current.category,
                action = "UPDATE",
                oldTitle = current.title,
                newTitle = current.title,
                oldDetails = current.details,
                newDetails = nextDetails,
                changedAt = timestamp
            )
        )
        updateHospitalRecordDetails(id, nextDetails)
        updateModuleRecordDetails(id, nextDetails)
        updateEmergencyCaseStatus(id, status)
    }

    @Query("DELETE FROM emergency_cases WHERE sourceRecordId = :id") suspend fun deleteEmergencyCase(id: Long)
    @Query("DELETE FROM icu_stays WHERE sourceRecordId = :id") suspend fun deleteIcuStay(id: Long)
    @Query("DELETE FROM inventory_items WHERE sourceRecordId = :id") suspend fun deleteInventoryItem(id: Long)
    @Query("DELETE FROM lab_orders WHERE sourceRecordId = :id") suspend fun deleteLabOrder(id: Long)
    @Query("DELETE FROM radiology_orders WHERE sourceRecordId = :id") suspend fun deleteRadiologyOrder(id: Long)
    @Query("DELETE FROM prescription_orders WHERE sourceRecordId = :id") suspend fun deletePrescriptionOrder(id: Long)
    @Query("DELETE FROM maintenance_tickets WHERE sourceRecordId = :id") suspend fun deleteMaintenanceTicket(id: Long)
    @Query("DELETE FROM vaccination_records WHERE sourceRecordId = :id") suspend fun deleteVaccinationRecord(id: Long)

    /**
     * Keep the legacy searchable timeline and the typed domain table in sync.
     * The original user-entered values remain in hospital_records for backward
     * compatibility; selected core modules also get queryable structured rows.
     */
    @Transaction
    suspend fun saveRecord(record: HospitalRecord, linkedPatientId: Long? = null, linkedDoctorId: Long? = null): Long {
        val sourceRecordId = insert(record)
        insertAuditLog(RecordAuditLogEntity(recordId = sourceRecordId, category = record.category, action = "CREATE", newTitle = record.title, newDetails = record.details, changedAt = System.currentTimeMillis()))
        insertModuleRecord(
            ModuleRecordEntity(
                sourceRecordId = sourceRecordId,
                category = record.category,
                title = record.title,
                details = record.details,
                createdAt = record.createdAt
            )
        )
        val fields = record.details.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) null
            else line.substring(0, separator).trim() to line.substring(separator + 2).trim()
        }.toMap()
        fun field(vararg names: String): String =
            names.firstNotNullOfOrNull { name -> fields[name]?.takeIf { it.isNotBlank() } }.orEmpty()

        when (record.category) {
            "encounters" -> saveClinicalVisit(ClinicalVisitEntity(
                sourceRecordId = sourceRecordId,
                patientId = linkedPatientId ?: findPatientIdByName(field("اسم المريض")),
                doctorId = linkedDoctorId ?: findDoctorIdByName(field("اسم الطبيب")),
                patientName = field("اسم المريض"),
                doctorName = field("اسم الطبيب"),
                chiefComplaint = field("الشكوى الرئيسية", "الشكوى"),
                diagnosis = field("التشخيص"),
                notes = field("ملاحظات", "التشخيص والملاحظات"),
                visitAt = field("تاريخ الزيارة", "التاريخ والوقت"),
                createdAt = record.createdAt
            ))
            "emergency" -> saveEmergencyCase(EmergencyCaseEntity(
                sourceRecordId = sourceRecordId, caseNumber = record.title,
                patientName = field("اسم المريض"), arrivalTime = field("وقت الوصول"), patientId = findPatientIdByName(field("اسم المريض")),
                triageLevel = field("درجة الفرز"), chiefComplaint = field("الشكوى الرئيسية"),
                status = field("حالة الحالة"), createdAt = record.createdAt
            ))
            "icu" -> saveIcuStay(IcuStayEntity(
                sourceRecordId = sourceRecordId, patientName = field("اسم المريض"),
                bedNumber = field("رقم السرير"), attendingDoctor = field("الطبيب المسؤول"),
                careLevel = field("مستوى الرعاية"), admissionDate = field("تاريخ الدخول"),
                teamNotes = field("ملاحظات الفريق"), createdAt = record.createdAt,
                patientId = findPatientIdByName(field("اسم المريض")), doctorId = findDoctorIdByName(field("الطبيب المسؤول"))
            ))
            "inventory" -> saveInventoryItem(InventoryItemEntity(
                sourceRecordId = sourceRecordId, name = field("اسم الصنف").ifBlank { record.title },
                sku = field("رمز الصنف").ifBlank { "ITEM-" + sourceRecordId },
                quantity = field("الكمية الحالية").toDoubleOrNull() ?: 0.0,
                reorderLevel = field("حد إعادة الطلب").toDoubleOrNull() ?: 0.0,
                unit = field("وحدة القياس"), notes = field("ملاحظات"), createdAt = record.createdAt
            ))
            "lab" -> saveLabOrder(LabOrderEntity(
                sourceRecordId = sourceRecordId, patientName = field("اسم المريض"), testName = field("اسم الفحص"),
                status = field("حالة الفحص"), result = field("النتيجة"), createdAt = record.createdAt,
                patientId = linkedPatientId ?: findPatientIdByName(field("اسم المريض")), clinicalVisitId = field("معرّف الزيارة").toLongOrNull()?.let { existingClinicalVisitId(it) }
            ))
            "radiology" -> saveRadiologyOrder(RadiologyOrderEntity(
                sourceRecordId = sourceRecordId, patientName = field("اسم المريض"), imagingType = field("نوع التصوير"),
                requestingDoctor = field("الطبيب الطالب"), status = field("الحالة"), report = field("التقرير"), createdAt = record.createdAt,
                patientId = findPatientIdByName(field("اسم المريض")), doctorId = findDoctorIdByName(field("الطبيب الطالب")),
                clinicalVisitId = field("معرّف الزيارة").toLongOrNull()?.let { existingClinicalVisitId(it) }
            ))
            "prescriptions" -> savePrescriptionOrder(PrescriptionOrderEntity(
                sourceRecordId = sourceRecordId, patientId = linkedPatientId ?: findPatientIdByName(field("اسم المريض")),
                doctorId = linkedDoctorId ?: findDoctorIdByName(field("اسم الطبيب")), clinicalVisitId = field("معرّف الزيارة").toLongOrNull()?.let { existingClinicalVisitId(it) },
                patientName = field("اسم المريض"), doctorName = field("اسم الطبيب"), medicationName = field("اسم الدواء"),
                dose = field("الجرعة"), frequencyAndDuration = field("التكرار والمدة"), status = field("الحالة"),
                instructions = field("تعليمات إضافية", "ملاحظات"), createdAt = record.createdAt
            ))
            "vaccination" -> saveVaccinationRecord(VaccinationRecordEntity(
                sourceRecordId = sourceRecordId,
                childName = field("اسم المريض", "اسم الطفل"),
                childFileNumber = field("رقم ملف الطفل", "رقم الملف"),
                dateOfBirth = field("تاريخ الميلاد"),
                vaccineName = field("اسم اللقاح"),
                doseNumber = field("رقم الجرعة"),
                administeredDate = field("تاريخ التطعيم"),
                nextDoseDate = field("موعد الجرعة التالية"),
                batchNumber = field("رقم التشغيلة"),
                administrationSite = field("مكان التطعيم"),
                administeredBy = field("الممارس الصحي"),
                doseStatus = field("حالة الجرعة").ifBlank { "تم التطعيم" },
                notes = field("ملاحظات"),
                createdAt = record.createdAt
            ))
            "maintenance" -> saveMaintenanceTicket(MaintenanceTicketEntity(
                sourceRecordId = sourceRecordId, ticketNumber = record.title,
                reporter = field("مقدم البلاغ"), location = field("الموقع"),
                issue = field("نوع العطل"), priority = field("الأولوية"),
                status = field("الحالة"), createdAt = record.createdAt
            ))
            "patients" -> upsertPatient(
                PatientEntity(
                    fileNumber = record.title,
                    fullName = field("اسم المريض").ifBlank { record.title },
                    phone = field("رقم الهاتف"),
                    age = field("العمر"),
                    notes = field("ملاحظات"),
                    createdAt = record.createdAt
                )
            )
            "doctors" -> upsertDoctor(
                DoctorEntity(
                    employeeNumber = record.title,
                    fullName = field("اسم الطبيب").ifBlank { record.title },
                    specialty = field("التخصص"),
                    phone = field("رقم الهاتف"),
                    createdAt = record.createdAt
                )
            )
            "appointments" -> insertAppointment(
                AppointmentEntity(
                    patientName = field("اسم المريض"),
                    doctorName = field("اسم الطبيب"),
                    patientId = findPatientIdByName(field("اسم المريض")),
                    doctorId = findDoctorIdByName(field("اسم الطبيب")),
                    appointmentAt = field("التاريخ والوقت"),
                    reason = field("سبب الزيارة"),
                    status = field("حالة الموعد", "الحالة").ifBlank { "مجدول" },
                    createdAt = record.createdAt
                )
            )
            "invoices" -> upsertInvoice(
                InvoiceEntity(
                    invoiceNumber = record.title,
                    patientName = field("اسم المريض"),
                    patientId = findPatientIdByName(field("اسم المريض")),
                    amountMinor = requireNotNull(
                        HospitalFieldValidation.toMinorUnitsOrNull(field("المبلغ"))
                    ) { "Invalid invoice amount" },
                    currency = field("العملة").ifBlank { "YER" },
                    status = field("الحالة").ifBlank { "open" },
                    createdAt = record.createdAt
                )
            )
            "payments" -> insertPayment(
                PaymentEntity(
                    invoiceNumber = field("رقم الفاتورة").ifBlank { record.title },
                    invoiceId = findInvoiceIdByNumber(field("رقم الفاتورة").ifBlank { record.title }),
                    amountMinor = requireNotNull(
                        HospitalFieldValidation.toMinorUnitsOrNull(field("المبلغ المدفوع"))
                    ) { "Invalid payment amount" },
                    method = field("طريقة الدفع"),
                    receiptReference = field("مرجع الإيصال"),
                    createdAt = record.createdAt
                )
            )
        }
        return sourceRecordId
    }

    /**
     * Update by the natural key instead of SQLite REPLACE. REPLACE deletes the
     * existing parent row first, which can silently null out foreign keys from
     * appointments/invoices/payments that point at it.
     */
    @Transaction
    suspend fun upsertPatient(patient: PatientEntity) {
        val updated = updatePatientByFileNumber(
            fileNumber = patient.fileNumber,
            fullName = patient.fullName,
            phone = patient.phone,
            age = patient.age,
            notes = patient.notes,
            createdAt = patient.createdAt
        )
        if (updated == 0) insertPatient(patient)
    }

    @Insert
    suspend fun insertPatient(patient: PatientEntity)

    @Query("UPDATE patients SET fullName = :fullName, phone = :phone, age = :age, notes = :notes, createdAt = :createdAt WHERE fileNumber = :fileNumber")
    suspend fun updatePatientByFileNumber(
        fileNumber: String,
        fullName: String,
        phone: String,
        age: String,
        notes: String,
        createdAt: Long
    ): Int

    @Transaction
    suspend fun upsertDoctor(doctor: DoctorEntity) {
        val updated = updateDoctorByEmployeeNumber(
            employeeNumber = doctor.employeeNumber,
            fullName = doctor.fullName,
            specialty = doctor.specialty,
            phone = doctor.phone,
            createdAt = doctor.createdAt
        )
        if (updated == 0) insertDoctor(doctor)
    }

    @Insert
    suspend fun insertDoctor(doctor: DoctorEntity)

    @Query("UPDATE doctors SET fullName = :fullName, specialty = :specialty, phone = :phone, createdAt = :createdAt WHERE employeeNumber = :employeeNumber")
    suspend fun updateDoctorByEmployeeNumber(
        employeeNumber: String,
        fullName: String,
        specialty: String,
        phone: String,
        createdAt: Long
    ): Int

    @Insert
    suspend fun insertAppointment(appointment: AppointmentEntity)

    @Transaction
    suspend fun upsertInvoice(invoice: InvoiceEntity) {
        val updated = updateInvoiceByNumber(
            invoiceNumber = invoice.invoiceNumber,
            patientName = invoice.patientName,
            patientId = invoice.patientId,
            amountMinor = invoice.amountMinor,
            currency = invoice.currency,
            status = invoice.status,
            createdAt = invoice.createdAt
        )
        if (updated == 0) insertInvoice(invoice)
    }

    @Insert
    suspend fun insertInvoice(invoice: InvoiceEntity)

    @Query("UPDATE invoices SET patientName = :patientName, patientId = :patientId, amountMinor = :amountMinor, currency = :currency, status = :status, createdAt = :createdAt WHERE invoiceNumber = :invoiceNumber")
    suspend fun updateInvoiceByNumber(
        invoiceNumber: String,
        patientName: String,
        patientId: Long?,
        amountMinor: Long,
        currency: String,
        status: String,
        createdAt: Long
    ): Int

    @Insert
    suspend fun insertPayment(payment: PaymentEntity)

    @Query("SELECT id FROM patients WHERE fullName = :name ORDER BY createdAt DESC LIMIT 1")
    suspend fun findPatientIdByName(name: String): Long?

    @Query("SELECT id FROM doctors WHERE fullName = :name ORDER BY createdAt DESC LIMIT 1")
    suspend fun findDoctorIdByName(name: String): Long?

    @Query("SELECT id FROM invoices WHERE invoiceNumber = :number LIMIT 1")
    suspend fun findInvoiceIdByNumber(number: String): Long?

    @Query("SELECT * FROM patients ORDER BY createdAt DESC")
    fun patients(): Flow<List<PatientEntity>>

    @Query("SELECT * FROM doctors ORDER BY createdAt DESC")
    fun doctors(): Flow<List<DoctorEntity>>

    @Query("SELECT * FROM appointments ORDER BY createdAt DESC")
    fun appointments(): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments WHERE patientId = :patientId OR (patientId IS NULL AND patientName = :patientName) ORDER BY appointmentAt DESC, createdAt DESC")
    fun appointmentsForPatient(patientId: Long, patientName: String): Flow<List<AppointmentEntity>>

    @Query("SELECT * FROM appointments WHERE id = :id LIMIT 1")
    suspend fun appointmentById(id: Long): AppointmentEntity?

    @Query("UPDATE appointments SET appointmentAt = :appointmentAt, reason = :reason, status = :status WHERE id = :id")
    suspend fun updateAppointmentRow(id: Long, appointmentAt: String, reason: String, status: String): Int

    @Query("UPDATE appointments SET status = :status WHERE id = :id")
    suspend fun updateAppointmentStatusRow(id: Long, status: String): Int

    @Transaction
    suspend fun updateAppointmentById(id: Long, appointmentAt: String, reason: String, status: String): Int {
        val appointment = appointmentById(id) ?: return 0
        val changed = updateAppointmentRow(id, appointmentAt, reason, status)
        if (changed > 0) syncAppointmentRecord(appointment, appointmentAt, reason, status, "UPDATE")
        return changed
    }

    @Transaction
    suspend fun updateAppointmentStatus(id: Long, status: String): Int {
        val appointment = appointmentById(id) ?: return 0
        val changed = updateAppointmentStatusRow(id, status)
        if (changed > 0) syncAppointmentRecord(appointment, appointment.appointmentAt, appointment.reason, status, "STATUS")
        return changed
    }

    @Query("SELECT * FROM hospital_records WHERE category = 'appointments' AND title = :title AND createdAt = :createdAt LIMIT 1")
    suspend fun appointmentSourceRecord(title: String, createdAt: Long): HospitalRecord?

    suspend fun syncAppointmentRecord(appointment: AppointmentEntity, appointmentAt: String, reason: String, status: String, action: String) {
        val source = appointmentSourceRecord("موعد مراجعة - ${appointment.patientName}", appointment.createdAt) ?: return
        val oldDetails = source.details
        val newDetails = oldDetails.lineSequence().map { line ->
            when {
                line.startsWith("التاريخ والوقت: ") -> "التاريخ والوقت: $appointmentAt"
                line.startsWith("سبب الزيارة: ") -> "سبب الزيارة: $reason"
                line.startsWith("حالة الموعد: ") -> "حالة الموعد: $status"
                else -> line
            }
        }.joinToString("\n")
        updateHospitalRecordDetails(source.id, newDetails)
        updateModuleRecordDetails(source.id, newDetails)
        insertAuditLog(RecordAuditLogEntity(recordId = source.id, category = "appointments", action = action, oldTitle = source.title, newTitle = source.title, oldDetails = oldDetails, newDetails = newDetails, changedAt = System.currentTimeMillis()))
    }

    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    fun invoices(): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM payments ORDER BY createdAt DESC")
    fun payments(): Flow<List<PaymentEntity>>

    @Query("SELECT COUNT(*) FROM hospital_records WHERE category = :category")
    suspend fun count(category: String): Int

    @Insert
    suspend fun insertAuditLog(entry: RecordAuditLogEntity)

    @Query("SELECT * FROM record_audit_log WHERE recordId = :recordId ORDER BY changedAt DESC")
    fun auditHistory(recordId: Long): Flow<List<RecordAuditLogEntity>>

    @Query("SELECT * FROM record_audit_log ORDER BY changedAt DESC LIMIT 500")
    fun recentAuditHistory(): Flow<List<RecordAuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePrescriptionOrder(order: PrescriptionOrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveClinicalVisit(visit: ClinicalVisitEntity)

    @Query("SELECT * FROM clinical_visits ORDER BY visitAt DESC, createdAt DESC")
    fun clinicalVisits(): Flow<List<ClinicalVisitEntity>>

    @Query("SELECT * FROM clinical_visits WHERE sourceRecordId = :sourceRecordId LIMIT 1")
    suspend fun clinicalVisitBySourceRecordId(sourceRecordId: Long): ClinicalVisitEntity?

    @Query("SELECT * FROM prescription_orders ORDER BY createdAt DESC")
    fun prescriptionOrders(): Flow<List<PrescriptionOrderEntity>>

    @Query("SELECT id FROM clinical_visits WHERE id = :id LIMIT 1")
    suspend fun existingClinicalVisitId(id: Long): Long?

    @Query("DELETE FROM clinical_visits WHERE sourceRecordId = :id")
    suspend fun deleteClinicalVisit(id: Long)

    @Query("""UPDATE clinical_visits SET patientId = :patientId, doctorId = :doctorId, patientName = :patientName,
        doctorName = :doctorName, chiefComplaint = :complaint, diagnosis = :diagnosis, notes = :notes, visitAt = :visitAt
        WHERE sourceRecordId = :id""")
    suspend fun updateClinicalVisit(id: Long, patientId: Long?, doctorId: Long?, patientName: String, doctorName: String,
        complaint: String, diagnosis: String, notes: String, visitAt: String): Int

    @Query("SELECT * FROM hospital_records WHERE id = :id LIMIT 1")
    suspend fun recordById(id: Long): HospitalRecord?

    @Query("SELECT COUNT(*) FROM hospital_records WHERE category = 'patients' AND title = :key AND id != :id")
    suspend fun countOtherPatientRecords(key: String, id: Long): Int

    @Query("DELETE FROM patients WHERE fileNumber = :key")
    suspend fun deletePatientRecord(key: String)

    @Query("SELECT COUNT(*) FROM hospital_records WHERE category = 'doctors' AND title = :key AND id != :id")
    suspend fun countOtherDoctorRecords(key: String, id: Long): Int

    @Query("DELETE FROM doctors WHERE employeeNumber = :key")
    suspend fun deleteDoctorRecord(key: String)

    @Query("DELETE FROM appointments WHERE patientName = :key AND createdAt = :createdAt")
    suspend fun deleteAppointmentRecord(key: String, createdAt: Long)

    @Query("DELETE FROM invoices WHERE invoiceNumber = :key AND createdAt = :createdAt")
    suspend fun deleteInvoiceRecord(key: String, createdAt: Long)

    @Query("DELETE FROM payments WHERE invoiceNumber = :key AND createdAt = :createdAt")
    suspend fun deletePaymentRecord(key: String, createdAt: Long)

    @Query("DELETE FROM hospital_records WHERE id = :id")
    suspend fun deleteRecordRow(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        val record = recordById(id) ?: return
        when (record.category) {
            "encounters" -> deleteClinicalVisit(id)
            "vaccination" -> deleteVaccinationRecord(id)
            "emergency" -> deleteEmergencyCase(id)
            "icu" -> deleteIcuStay(id)
            "inventory" -> deleteInventoryItem(id)
            "lab" -> deleteLabOrder(id)
            "radiology" -> deleteRadiologyOrder(id)
            "prescriptions" -> deletePrescriptionOrder(id)
            "maintenance" -> deleteMaintenanceTicket(id)
            "patients" -> {
                if (countOtherPatientRecords(record.title, id) == 0) deletePatientRecord(record.title)
            }
            "doctors" -> {
                if (countOtherDoctorRecords(record.title, id) == 0) deleteDoctorRecord(record.title)
            }
            "appointments" -> deleteAppointmentRecord(record.title, record.createdAt)
            "invoices" -> deleteInvoiceRecord(record.title, record.createdAt)
            "payments" -> deletePaymentRecord(record.title, record.createdAt)
        }
        insertAuditLog(RecordAuditLogEntity(recordId = id, category = record.category, action = "DELETE", oldTitle = record.title, oldDetails = record.details, changedAt = System.currentTimeMillis()))
        deleteRecordRow(id)
        deleteModuleRecord(id)
    }
}

@Database(
    entities = [
        HospitalRecord::class,
        ModuleRecordEntity::class,
        EmergencyCaseEntity::class,
        IcuStayEntity::class,
        InventoryItemEntity::class,
        LabOrderEntity::class,
        RadiologyOrderEntity::class,
        PrescriptionOrderEntity::class,
        MaintenanceTicketEntity::class,
        PatientEntity::class,
        DoctorEntity::class,
        AppointmentEntity::class,
        InvoiceEntity::class,
        PaymentEntity::class,
        BusinessProfileEntity::class,
        VaccinationRecordEntity::class,
        ReferenceCatalogEntity::class,
        RecordAuditLogEntity::class,
        ClinicalVisitEntity::class
    ],
    version = 16,
    exportSchema = false
)
abstract class HospitalDatabase : RoomDatabase() {
    abstract fun hospitalDao(): HospitalDao

    companion object {
        @Volatile private var INSTANCE: HospitalDatabase? = null

        /**
         * Version 1 only contained hospital_records. CREATE TABLE IF NOT EXISTS leaves
         * those rows untouched and adds the new typed tables without destructive fallback.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS clinical_visits (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, patientId INTEGER, doctorId INTEGER, patientName TEXT NOT NULL, doctorName TEXT NOT NULL, chiefComplaint TEXT NOT NULL, diagnosis TEXT NOT NULL, notes TEXT NOT NULL, visitAt TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_clinical_visits_sourceRecordId ON clinical_visits(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clinical_visits_patientId ON clinical_visits(patientId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clinical_visits_doctorId ON clinical_visits(doctorId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clinical_visits_visitAt ON clinical_visits(visitAt)")
                db.execSQL("INSERT OR IGNORE INTO clinical_visits (sourceRecordId, patientName, doctorName, chiefComplaint, diagnosis, notes, visitAt, createdAt) SELECT id, title, '', details, '', '', '', createdAt FROM hospital_records WHERE category = 'encounters'")
            }
        }

        /**
         * Version 11 enforces referential integrity for clinical visits while
         * preserving existing rows and backfilling links where names match.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE emergency_cases_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, caseNumber TEXT NOT NULL, patientName TEXT NOT NULL, arrivalTime TEXT NOT NULL, triageLevel TEXT NOT NULL, chiefComplaint TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, patientId INTEGER, FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
                db.execSQL("""INSERT INTO emergency_cases_new (id, sourceRecordId, caseNumber, patientName, arrivalTime, triageLevel, chiefComplaint, status, createdAt, patientId) SELECT e.id, e.sourceRecordId, e.caseNumber, e.patientName, e.arrivalTime, e.triageLevel, e.chiefComplaint, e.status, e.createdAt, (SELECT p.id FROM patients p WHERE p.fullName = e.patientName ORDER BY p.createdAt DESC, p.id DESC LIMIT 1) FROM emergency_cases e INNER JOIN hospital_records h ON h.id = e.sourceRecordId""")
                db.execSQL("DROP TABLE emergency_cases")
                db.execSQL("ALTER TABLE emergency_cases_new RENAME TO emergency_cases")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_emergency_cases_sourceRecordId ON emergency_cases(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emergency_cases_patientId ON emergency_cases(patientId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emergency_cases_triageLevel_createdAt ON emergency_cases(triageLevel, createdAt)")

                db.execSQL("""CREATE TABLE icu_stays_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, patientName TEXT NOT NULL, bedNumber TEXT NOT NULL, attendingDoctor TEXT NOT NULL, careLevel TEXT NOT NULL, admissionDate TEXT NOT NULL, teamNotes TEXT NOT NULL, createdAt INTEGER NOT NULL, patientId INTEGER, doctorId INTEGER, FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(doctorId) REFERENCES doctors(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
                db.execSQL("""INSERT INTO icu_stays_new (id, sourceRecordId, patientName, bedNumber, attendingDoctor, careLevel, admissionDate, teamNotes, createdAt, patientId, doctorId) SELECT u.id, u.sourceRecordId, u.patientName, u.bedNumber, u.attendingDoctor, u.careLevel, u.admissionDate, u.teamNotes, u.createdAt, (SELECT p.id FROM patients p WHERE p.fullName = u.patientName ORDER BY p.createdAt DESC, p.id DESC LIMIT 1), (SELECT d.id FROM doctors d WHERE d.fullName = u.attendingDoctor ORDER BY d.createdAt DESC, d.id DESC LIMIT 1) FROM icu_stays u INNER JOIN hospital_records h ON h.id = u.sourceRecordId""")
                db.execSQL("DROP TABLE icu_stays")
                db.execSQL("ALTER TABLE icu_stays_new RENAME TO icu_stays")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_icu_stays_sourceRecordId ON icu_stays(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_icu_stays_patientId ON icu_stays(patientId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_icu_stays_doctorId ON icu_stays(doctorId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_icu_stays_bedNumber ON icu_stays(bedNumber)")

                db.execSQL("""CREATE TABLE lab_orders_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, patientName TEXT NOT NULL, testName TEXT NOT NULL, status TEXT NOT NULL, result TEXT NOT NULL, createdAt INTEGER NOT NULL, patientId INTEGER, FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
                db.execSQL("""INSERT INTO lab_orders_new (id, sourceRecordId, patientName, testName, status, result, createdAt, patientId) SELECT l.id, l.sourceRecordId, l.patientName, l.testName, l.status, l.result, l.createdAt, (SELECT p.id FROM patients p WHERE p.fullName = l.patientName ORDER BY p.createdAt DESC, p.id DESC LIMIT 1) FROM lab_orders l INNER JOIN hospital_records h ON h.id = l.sourceRecordId""")
                db.execSQL("DROP TABLE lab_orders")
                db.execSQL("ALTER TABLE lab_orders_new RENAME TO lab_orders")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lab_orders_sourceRecordId ON lab_orders(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lab_orders_patientId ON lab_orders(patientId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lab_orders_status_createdAt ON lab_orders(status, createdAt)")

                db.execSQL("""CREATE TABLE radiology_orders_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, patientName TEXT NOT NULL, imagingType TEXT NOT NULL, requestingDoctor TEXT NOT NULL, status TEXT NOT NULL, report TEXT NOT NULL, createdAt INTEGER NOT NULL, patientId INTEGER, doctorId INTEGER, FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(doctorId) REFERENCES doctors(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
                db.execSQL("""INSERT INTO radiology_orders_new (id, sourceRecordId, patientName, imagingType, requestingDoctor, status, report, createdAt, patientId, doctorId) SELECT r.id, r.sourceRecordId, r.patientName, r.imagingType, r.requestingDoctor, r.status, r.report, r.createdAt, (SELECT p.id FROM patients p WHERE p.fullName = r.patientName ORDER BY p.createdAt DESC, p.id DESC LIMIT 1), (SELECT d.id FROM doctors d WHERE d.fullName = r.requestingDoctor ORDER BY d.createdAt DESC, d.id DESC LIMIT 1) FROM radiology_orders r INNER JOIN hospital_records h ON h.id = r.sourceRecordId""")
                db.execSQL("DROP TABLE radiology_orders")
                db.execSQL("ALTER TABLE radiology_orders_new RENAME TO radiology_orders")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_radiology_orders_sourceRecordId ON radiology_orders(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radiology_orders_patientId ON radiology_orders(patientId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radiology_orders_doctorId ON radiology_orders(doctorId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radiology_orders_status_createdAt ON radiology_orders(status, createdAt)")
            }
        }

        /**
         * Audit rows are historical evidence, not editable application data.
         * Corrections must be recorded as a new event rather than rewriting history.
         */

        /**
         * Version 15 brings the core server workflows into the offline database:
         * medicine stock ledger, bed/admission lifecycle, appointment overlap rules,
         * prescription dispensing state, and lab status history.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    actor TEXT NOT NULL DEFAULT 'local-user',
                    action TEXT NOT NULL,
                    entityType TEXT NOT NULL,
                    entityId TEXT NOT NULL,
                    detailsJson TEXT NOT NULL DEFAULT '{}',
                    occurredAt TEXT NOT NULL
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_audit_entity_timeline ON local_audit_log(entityType, entityId, id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_audit_timeline ON local_audit_log(id DESC)")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_admissions_state_insert
                    BEFORE INSERT ON local_admissions
                    WHEN (NEW.status='admitted' AND NEW.dischargedAt IS NOT NULL)
                      OR (NEW.status='discharged' AND NEW.dischargedAt IS NULL)
                    BEGIN SELECT RAISE(ABORT, 'admission status and discharge time are inconsistent'); END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_admissions_state_update
                    BEFORE UPDATE OF status, dischargedAt ON local_admissions
                    WHEN (NEW.status='admitted' AND NEW.dischargedAt IS NOT NULL)
                      OR (NEW.status='discharged' AND NEW.dischargedAt IS NULL)
                      OR (OLD.status='discharged' AND NEW.status='admitted')
                    BEGIN SELECT RAISE(ABORT, 'invalid admission state transition'); END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_audit_log_no_update
                    BEFORE UPDATE ON local_audit_log BEGIN
                    SELECT RAISE(ABORT, 'local audit history is append-only');
                END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_audit_log_no_delete
                    BEFORE DELETE ON local_audit_log BEGIN
                    SELECT RAISE(ABORT, 'local audit history is append-only');
                END""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_finance_outbox (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    eventKey TEXT NOT NULL UNIQUE,
                    eventType TEXT NOT NULL CHECK(eventType IN ('hospital.invoice.created','hospital.payment.received')),
                    aggregateType TEXT NOT NULL CHECK(aggregateType IN ('invoice','payment')),
                    aggregateId TEXT NOT NULL,
                    payloadJson TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','processing','delivered','failed')),
                    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL,
                    deliveredAt TEXT,
                    lastError TEXT
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_finance_outbox_pending ON local_finance_outbox(status, id)")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_medicines (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL,
                    sku TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 0 CHECK(quantity >= 0),
                    reorderLevel INTEGER NOT NULL DEFAULT 0 CHECK(reorderLevel >= 0),
                    unit TEXT NOT NULL DEFAULT 'unit',
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    updatedAt TEXT NOT NULL,
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE
                )""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_local_medicines_name ON local_medicines(name COLLATE NOCASE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_medicines_stock ON local_medicines(quantity, reorderLevel)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_medicine_movements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL UNIQUE,
                    medicineId INTEGER NOT NULL,
                    movementType TEXT NOT NULL CHECK(movementType IN ('initial_stock','restock','dispense')),
                    quantityDelta INTEGER NOT NULL CHECK(quantityDelta <> 0),
                    balanceAfter INTEGER NOT NULL CHECK(balanceAfter >= 0),
                    reference TEXT NOT NULL DEFAULT '',
                    actor TEXT NOT NULL DEFAULT 'local-user',
                    occurredAt TEXT NOT NULL,
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE,
                    FOREIGN KEY(medicineId) REFERENCES local_medicines(id)
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_medicine_movements_timeline ON local_medicine_movements(medicineId, id)")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_medicine_movements_no_update
                    BEFORE UPDATE ON local_medicine_movements BEGIN
                    SELECT RAISE(ABORT, 'medicine stock movements are append-only');
                END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_medicine_movements_no_delete
                    BEFORE DELETE ON local_medicine_movements BEGIN
                    SELECT RAISE(ABORT, 'medicine stock movements are append-only');
                END""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_medicine_batches (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    medicineId INTEGER NOT NULL,
                    batchNo TEXT NOT NULL,
                    expiryDate TEXT,
                    quantity INTEGER NOT NULL DEFAULT 0 CHECK(quantity >= 0),
                    receivedAt TEXT NOT NULL,
                    reference TEXT NOT NULL DEFAULT '',
                    createdBy TEXT NOT NULL DEFAULT 'local-user',
                    UNIQUE(medicineId, batchNo),
                    CHECK(expiryDate IS NULL OR (length(expiryDate)=10 AND date(expiryDate) IS NOT NULL AND date(expiryDate)=expiryDate)),
                    FOREIGN KEY(medicineId) REFERENCES local_medicines(id)
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_medicine_batches_fefo ON local_medicine_batches(medicineId, expiryDate, id)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_medicine_batch_movements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL,
                    batchId INTEGER NOT NULL,
                    medicineId INTEGER NOT NULL,
                    movementType TEXT NOT NULL CHECK(movementType IN ('initial_stock','restock','dispense')),
                    quantityDelta INTEGER NOT NULL CHECK(quantityDelta <> 0),
                    balanceAfter INTEGER NOT NULL CHECK(balanceAfter >= 0),
                    reference TEXT NOT NULL DEFAULT '',
                    actor TEXT NOT NULL DEFAULT 'local-user',
                    occurredAt TEXT NOT NULL,
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE,
                    FOREIGN KEY(batchId) REFERENCES local_medicine_batches(id),
                    FOREIGN KEY(medicineId) REFERENCES local_medicines(id)
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_batch_movements_timeline ON local_medicine_batch_movements(batchId, id)")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_medicine_batch_movements_no_update
                    BEFORE UPDATE ON local_medicine_batch_movements BEGIN
                    SELECT RAISE(ABORT, 'medicine batch movements are append-only');
                END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_medicine_batch_movements_no_delete
                    BEFORE DELETE ON local_medicine_batch_movements BEGIN
                    SELECT RAISE(ABORT, 'medicine batch movements are append-only');
                END""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_beds (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL UNIQUE,
                    ward TEXT NOT NULL,
                    bedNo TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    notes TEXT NOT NULL DEFAULT '',
                    UNIQUE(ward, bedNo),
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_beds_active ON local_beds(active, ward)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_admissions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL UNIQUE,
                    patientName TEXT NOT NULL,
                    ward TEXT NOT NULL,
                    bedNo TEXT NOT NULL,
                    admittedAt TEXT NOT NULL,
                    dischargedAt TEXT,
                    reason TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL CHECK(status IN ('admitted','discharged')),
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_admissions_status_bed ON local_admissions(status, ward, bedNo)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_admissions_patient ON local_admissions(patientName, status)")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_admissions_one_active_bed_insert
                    BEFORE INSERT ON local_admissions WHEN NEW.status='admitted'
                    AND EXISTS(SELECT 1 FROM local_admissions a WHERE a.ward=NEW.ward AND a.bedNo=NEW.bedNo AND a.status='admitted')
                    BEGIN SELECT RAISE(ABORT, 'bed is already occupied'); END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_admissions_one_active_bed_update
                    BEFORE UPDATE OF ward, bedNo, status ON local_admissions WHEN NEW.status='admitted'
                    AND EXISTS(SELECT 1 FROM local_admissions a WHERE a.id<>NEW.id AND a.ward=NEW.ward AND a.bedNo=NEW.bedNo AND a.status='admitted')
                    BEGIN SELECT RAISE(ABORT, 'bed is already occupied'); END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_admissions_one_active_patient_insert
                    BEFORE INSERT ON local_admissions WHEN NEW.status='admitted'
                    AND EXISTS(SELECT 1 FROM local_admissions a WHERE a.patientName=NEW.patientName COLLATE NOCASE AND a.status='admitted')
                    BEGIN SELECT RAISE(ABORT, 'patient already has an active admission'); END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_admissions_one_active_patient_update
                    BEFORE UPDATE OF patientName, status ON local_admissions WHEN NEW.status='admitted'
                    AND EXISTS(SELECT 1 FROM local_admissions a WHERE a.id<>NEW.id AND a.patientName=NEW.patientName COLLATE NOCASE AND a.status='admitted')
                    BEGIN SELECT RAISE(ABORT, 'patient already has an active admission'); END""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_appointments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL UNIQUE,
                    patientName TEXT NOT NULL,
                    doctorName TEXT NOT NULL,
                    startsAt TEXT NOT NULL,
                    endsAt TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'scheduled' CHECK(status IN ('scheduled','checked_in','completed','cancelled','no_show')),
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE,
                    CHECK(endsAt > startsAt)
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_appointments_doctor_time ON local_appointments(doctorName, startsAt, endsAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_appointments_patient_time ON local_appointments(patientName, startsAt, endsAt)")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_appointments_no_doctor_overlap_insert
                    BEFORE INSERT ON local_appointments WHEN NEW.status IN ('scheduled','checked_in')
                    AND EXISTS(SELECT 1 FROM local_appointments a WHERE a.doctorName=NEW.doctorName COLLATE NOCASE
                    AND a.status IN ('scheduled','checked_in') AND a.startsAt<NEW.endsAt AND a.endsAt>NEW.startsAt)
                    BEGIN SELECT RAISE(ABORT, 'doctor already has an overlapping appointment'); END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_appointments_no_patient_overlap_insert
                    BEFORE INSERT ON local_appointments WHEN NEW.status IN ('scheduled','checked_in')
                    AND EXISTS(SELECT 1 FROM local_appointments a WHERE a.patientName=NEW.patientName COLLATE NOCASE
                    AND a.status IN ('scheduled','checked_in') AND a.startsAt<NEW.endsAt AND a.endsAt>NEW.startsAt)
                    BEGIN SELECT RAISE(ABORT, 'patient already has an overlapping appointment'); END""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_prescriptions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL UNIQUE,
                    medicineId INTEGER,
                    medicationName TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 0 CHECK(quantity >= 0),
                    status TEXT NOT NULL CHECK(status IN ('pending','dispensed','cancelled')),
                    dispensedAt TEXT,
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE,
                    FOREIGN KEY(medicineId) REFERENCES local_medicines(id)
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_lab_order_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('ordered','collected','processing','resulted','cancelled')),
                    result TEXT NOT NULL DEFAULT '',
                    occurredAt TEXT NOT NULL,
                    actor TEXT NOT NULL DEFAULT 'local-user',
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_lab_events_timeline ON local_lab_order_events(sourceRecordId, id)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL UNIQUE,
                    username TEXT NOT NULL UNIQUE,
                    role TEXT NOT NULL CHECK(role IN ('admin','doctor','nurse','reception','lab','pharmacist','accountant')),
                    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE
                )""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS local_encounters (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceRecordId INTEGER NOT NULL UNIQUE,
                    patientName TEXT NOT NULL,
                    doctorName TEXT NOT NULL DEFAULT '',
                    openedAt TEXT NOT NULL,
                    closedAt TEXT,
                    chiefComplaint TEXT NOT NULL DEFAULT '',
                    diagnosis TEXT NOT NULL DEFAULT '',
                    notes TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL CHECK(status IN ('open','closed')),
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON DELETE CASCADE,
                    CHECK((status='open' AND closedAt IS NULL) OR (status='closed' AND closedAt IS NOT NULL))
                )""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_local_encounters_patient_status ON local_encounters(patientName, status)")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_encounters_state_insert
                    BEFORE INSERT ON local_encounters
                    WHEN (NEW.status='open' AND NEW.closedAt IS NOT NULL) OR (NEW.status='closed' AND NEW.closedAt IS NULL)
                    BEGIN SELECT RAISE(ABORT, 'encounter status and closure timestamp are inconsistent'); END""")
                db.execSQL("""CREATE TRIGGER IF NOT EXISTS local_encounters_state_update
                    BEFORE UPDATE OF status, closedAt ON local_encounters
                    WHEN (NEW.status='open' AND NEW.closedAt IS NOT NULL) OR (NEW.status='closed' AND NEW.closedAt IS NULL)
                    BEGIN SELECT RAISE(ABORT, 'encounter status and closure timestamp are inconsistent'); END""")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS record_audit_log_reject_update
                    BEFORE UPDATE ON record_audit_log
                    BEGIN
                        SELECT RAISE(ABORT, 'record_audit_log is append-only');
                    END
                """.trimIndent())
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS record_audit_log_reject_delete
                    BEFORE DELETE ON record_audit_log
                    BEGIN
                        SELECT RAISE(ABORT, 'record_audit_log is append-only');
                    END
                """.trimIndent())
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lab_orders ADD COLUMN clinicalVisitId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lab_orders_clinicalVisitId ON lab_orders(clinicalVisitId)")
                db.execSQL("ALTER TABLE radiology_orders ADD COLUMN clinicalVisitId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radiology_orders_clinicalVisitId ON radiology_orders(clinicalVisitId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS prescription_orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL,
                    patientId INTEGER, doctorId INTEGER, clinicalVisitId INTEGER,
                    patientName TEXT NOT NULL, doctorName TEXT NOT NULL, medicationName TEXT NOT NULL,
                    dose TEXT NOT NULL, frequencyAndDuration TEXT NOT NULL, status TEXT NOT NULL,
                    instructions TEXT NOT NULL, createdAt INTEGER NOT NULL,
                    FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(doctorId) REFERENCES doctors(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                    FOREIGN KEY(clinicalVisitId) REFERENCES clinical_visits(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )""".trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_prescription_orders_sourceRecordId ON prescription_orders(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prescription_orders_patientId ON prescription_orders(patientId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prescription_orders_doctorId ON prescription_orders(doctorId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prescription_orders_clinicalVisitId ON prescription_orders(clinicalVisitId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_prescription_orders_status_createdAt ON prescription_orders(status, createdAt)")
                db.execSQL("""INSERT OR IGNORE INTO prescription_orders
                    (sourceRecordId, patientId, doctorId, clinicalVisitId, patientName, doctorName, medicationName, dose, frequencyAndDuration, status, instructions, createdAt)
                    SELECT h.id, NULL, NULL, NULL, '', '', h.title, '', '', '', h.details, h.createdAt
                    FROM hospital_records h WHERE h.category = 'prescriptions'""".trimIndent())

            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE clinical_visits_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceRecordId INTEGER NOT NULL,
                        patientId INTEGER,
                        doctorId INTEGER,
                        patientName TEXT NOT NULL,
                        doctorName TEXT NOT NULL,
                        chiefComplaint TEXT NOT NULL,
                        diagnosis TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        visitAt TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sourceRecordId) REFERENCES hospital_records(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(doctorId) REFERENCES doctors(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO clinical_visits_new (
                        id, sourceRecordId, patientId, doctorId, patientName, doctorName,
                        chiefComplaint, diagnosis, notes, visitAt, createdAt
                    )
                    SELECT
                        v.id,
                        v.sourceRecordId,
                        COALESCE(
                            (SELECT p.id FROM patients p WHERE p.id = v.patientId),
                            (SELECT p.id FROM patients p WHERE p.fullName = v.patientName ORDER BY p.createdAt DESC, p.id DESC LIMIT 1)
                        ),
                        COALESCE(
                            (SELECT d.id FROM doctors d WHERE d.id = v.doctorId),
                            (SELECT d.id FROM doctors d WHERE d.fullName = v.doctorName ORDER BY d.createdAt DESC, d.id DESC LIMIT 1)
                        ),
                        v.patientName, v.doctorName, v.chiefComplaint, v.diagnosis, v.notes, v.visitAt, v.createdAt
                    FROM clinical_visits v
                    INNER JOIN hospital_records h ON h.id = v.sourceRecordId
                """.trimIndent())
                db.execSQL("DROP TABLE clinical_visits")
                db.execSQL("ALTER TABLE clinical_visits_new RENAME TO clinical_visits")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_clinical_visits_sourceRecordId ON clinical_visits(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clinical_visits_patientId ON clinical_visits(patientId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clinical_visits_doctorId ON clinical_visits(doctorId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clinical_visits_visitAt ON clinical_visits(visitAt)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS record_audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recordId INTEGER NOT NULL, category TEXT NOT NULL, action TEXT NOT NULL, oldTitle TEXT NOT NULL, newTitle TEXT NOT NULL, oldDetails TEXT NOT NULL, newDetails TEXT NOT NULL, changedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_audit_log_recordId_changedAt ON record_audit_log(recordId, changedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_record_audit_log_category_changedAt ON record_audit_log(category, changedAt)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reference_catalog (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, category TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, active INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reference_catalog_category_name ON reference_catalog(category, name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reference_catalog_category_active ON reference_catalog(category, active)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS vaccination_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, childName TEXT NOT NULL, childFileNumber TEXT NOT NULL, dateOfBirth TEXT NOT NULL, vaccineName TEXT NOT NULL, doseNumber TEXT NOT NULL, administeredDate TEXT NOT NULL, nextDoseDate TEXT NOT NULL, batchNumber TEXT NOT NULL, administrationSite TEXT NOT NULL, administeredBy TEXT NOT NULL, doseStatus TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_vaccination_records_sourceRecordId ON vaccination_records(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vaccination_records_childFileNumber ON vaccination_records(childFileNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vaccination_records_nextDoseDate ON vaccination_records(nextDoseDate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_vaccination_records_childName_vaccineName ON vaccination_records(childName, vaccineName)")
                // Existing vaccination entries remain intact in hospital_records; new entries are also stored in this typed table.
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS business_profile (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, legalName TEXT NOT NULL, businessType TEXT NOT NULL, registrationNumber TEXT NOT NULL, taxNumber TEXT NOT NULL, phone TEXT NOT NULL, email TEXT NOT NULL, address TEXT NOT NULL, website TEXT NOT NULL, currency TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS emergency_cases (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, caseNumber TEXT NOT NULL, patientName TEXT NOT NULL, arrivalTime TEXT NOT NULL, triageLevel TEXT NOT NULL, chiefComplaint TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_emergency_cases_sourceRecordId ON emergency_cases(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emergency_cases_triageLevel_createdAt ON emergency_cases(triageLevel, createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS icu_stays (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, patientName TEXT NOT NULL, bedNumber TEXT NOT NULL, attendingDoctor TEXT NOT NULL, careLevel TEXT NOT NULL, admissionDate TEXT NOT NULL, teamNotes TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_icu_stays_sourceRecordId ON icu_stays(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_icu_stays_bedNumber ON icu_stays(bedNumber)")
                db.execSQL("CREATE TABLE IF NOT EXISTS inventory_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, name TEXT NOT NULL, sku TEXT NOT NULL, quantity REAL NOT NULL, reorderLevel REAL NOT NULL, unit TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_inventory_items_sourceRecordId ON inventory_items(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_items_sku ON inventory_items(sku)")
                db.execSQL("CREATE TABLE IF NOT EXISTS lab_orders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, patientName TEXT NOT NULL, testName TEXT NOT NULL, status TEXT NOT NULL, result TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_lab_orders_sourceRecordId ON lab_orders(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lab_orders_status_createdAt ON lab_orders(status, createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS radiology_orders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, patientName TEXT NOT NULL, imagingType TEXT NOT NULL, requestingDoctor TEXT NOT NULL, status TEXT NOT NULL, report TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_radiology_orders_sourceRecordId ON radiology_orders(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radiology_orders_status_createdAt ON radiology_orders(status, createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS maintenance_tickets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, ticketNumber TEXT NOT NULL, reporter TEXT NOT NULL, location TEXT NOT NULL, issue TEXT NOT NULL, priority TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_maintenance_tickets_sourceRecordId ON maintenance_tickets(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_maintenance_tickets_status_createdAt ON maintenance_tickets(status, createdAt)")
                db.execSQL("INSERT OR IGNORE INTO emergency_cases (sourceRecordId, caseNumber, patientName, arrivalTime, triageLevel, chiefComplaint, status, createdAt) SELECT id, title, '', '', '', details, '', createdAt FROM hospital_records WHERE category = 'emergency'")
                db.execSQL("INSERT OR IGNORE INTO icu_stays (sourceRecordId, patientName, bedNumber, attendingDoctor, careLevel, admissionDate, teamNotes, createdAt) SELECT id, '', '', '', '', '', details, createdAt FROM hospital_records WHERE category = 'icu'")
                db.execSQL("INSERT OR IGNORE INTO inventory_items (sourceRecordId, name, sku, quantity, reorderLevel, unit, notes, createdAt) SELECT id, title, 'LEGACY-' || id, 0, 0, '', details, createdAt FROM hospital_records WHERE category = 'inventory'")
                db.execSQL("INSERT OR IGNORE INTO lab_orders (sourceRecordId, patientName, testName, status, result, createdAt) SELECT id, '', title, '', details, createdAt FROM hospital_records WHERE category = 'lab'")
                db.execSQL("INSERT OR IGNORE INTO radiology_orders (sourceRecordId, patientName, imagingType, requestingDoctor, status, report, createdAt) SELECT id, '', '', '', '', details, createdAt FROM hospital_records WHERE category = 'radiology'")
                db.execSQL("INSERT OR IGNORE INTO maintenance_tickets (sourceRecordId, ticketNumber, reporter, location, issue, priority, status, createdAt) SELECT id, title, '', '', details, '', '', createdAt FROM hospital_records WHERE category = 'maintenance'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS module_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceRecordId INTEGER NOT NULL, category TEXT NOT NULL, title TEXT NOT NULL, details TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_module_records_sourceRecordId ON module_records(sourceRecordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_module_records_category_createdAt ON module_records(category, createdAt)")
                // Preserve and index all rows already present in the legacy table.
                db.execSQL("INSERT OR IGNORE INTO module_records (sourceRecordId, category, title, details, createdAt) SELECT id, category, title, details, createdAt FROM hospital_records")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hospital_records_category_createdAt ON hospital_records(category, createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hospital_records_title ON hospital_records(title)")
                db.execSQL("CREATE TABLE IF NOT EXISTS patients (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fileNumber TEXT NOT NULL, fullName TEXT NOT NULL, phone TEXT NOT NULL, age TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_patients_fileNumber ON patients(fileNumber)")
                db.execSQL("CREATE TABLE IF NOT EXISTS doctors (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, employeeNumber TEXT NOT NULL, fullName TEXT NOT NULL, specialty TEXT NOT NULL, phone TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_doctors_employeeNumber ON doctors(employeeNumber)")
                db.execSQL("CREATE TABLE IF NOT EXISTS appointments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, patientName TEXT NOT NULL, doctorName TEXT NOT NULL, appointmentAt TEXT NOT NULL, reason TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_appointments_patientName ON appointments(patientName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_appointments_doctorName ON appointments(doctorName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_appointments_appointmentAt ON appointments(appointmentAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS invoices (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, invoiceNumber TEXT NOT NULL, patientName TEXT NOT NULL, amountMinor INTEGER NOT NULL, currency TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_invoices_invoiceNumber ON invoices(invoiceNumber)")
                db.execSQL("CREATE TABLE IF NOT EXISTS payments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, invoiceNumber TEXT NOT NULL, amountMinor INTEGER NOT NULL, method TEXT NOT NULL, receiptReference TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_payments_invoiceNumber ON payments(invoiceNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_payments_createdAt ON payments(createdAt)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE appointments RENAME TO appointments_old")
                db.execSQL("CREATE TABLE appointments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, patientName TEXT NOT NULL, doctorName TEXT NOT NULL, patientId INTEGER, doctorId INTEGER, appointmentAt TEXT NOT NULL, reason TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(doctorId) REFERENCES doctors(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("INSERT INTO appointments (id, patientName, doctorName, patientId, doctorId, appointmentAt, reason, status, createdAt) SELECT a.id, a.patientName, a.doctorName, (SELECT p.id FROM patients p WHERE p.fullName = a.patientName ORDER BY p.createdAt DESC LIMIT 1), (SELECT d.id FROM doctors d WHERE d.fullName = a.doctorName ORDER BY d.createdAt DESC LIMIT 1), a.appointmentAt, a.reason, a.status, a.createdAt FROM appointments_old a")
                db.execSQL("DROP TABLE appointments_old")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_appointments_patientId ON appointments(patientId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_appointments_doctorId ON appointments(doctorId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_appointments_patientName ON appointments(patientName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_appointments_doctorName ON appointments(doctorName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_appointments_appointmentAt ON appointments(appointmentAt)")

                db.execSQL("ALTER TABLE invoices RENAME TO invoices_old")
                db.execSQL("CREATE TABLE invoices (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, invoiceNumber TEXT NOT NULL, patientName TEXT NOT NULL, patientId INTEGER, amountMinor INTEGER NOT NULL, currency TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("INSERT INTO invoices (id, invoiceNumber, patientName, patientId, amountMinor, currency, status, createdAt) SELECT i.id, i.invoiceNumber, i.patientName, (SELECT p.id FROM patients p WHERE p.fullName = i.patientName ORDER BY p.createdAt DESC LIMIT 1), i.amountMinor, i.currency, i.status, i.createdAt FROM invoices_old i")
                db.execSQL("DROP TABLE invoices_old")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_invoices_invoiceNumber ON invoices(invoiceNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoices_patientId ON invoices(patientId)")

                db.execSQL("ALTER TABLE payments RENAME TO payments_old")
                db.execSQL("CREATE TABLE payments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, invoiceNumber TEXT NOT NULL, invoiceId INTEGER, amountMinor INTEGER NOT NULL, method TEXT NOT NULL, receiptReference TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(invoiceId) REFERENCES invoices(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("INSERT INTO payments (id, invoiceNumber, invoiceId, amountMinor, method, receiptReference, createdAt) SELECT p.id, p.invoiceNumber, (SELECT i.id FROM invoices i WHERE i.invoiceNumber = p.invoiceNumber LIMIT 1), p.amountMinor, p.method, p.receiptReference, p.createdAt FROM payments_old p")
                db.execSQL("DROP TABLE payments_old")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_payments_invoiceNumber ON payments(invoiceNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_payments_invoiceId ON payments(invoiceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_payments_createdAt ON payments(createdAt)")
            }
        }

        private fun installAuditImmutability(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS record_audit_log_reject_update
                BEFORE UPDATE ON record_audit_log
                BEGIN
                    SELECT RAISE(ABORT, 'record_audit_log is append-only');
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS record_audit_log_reject_delete
                BEFORE DELETE ON record_audit_log
                BEGIN
                    SELECT RAISE(ABORT, 'record_audit_log is append-only');
                END
            """.trimIndent())
        }

        fun get(context: Context): HospitalDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HospitalDatabase::class.java,
                    "hospital-local.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            installAuditImmutability(db)
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            installAuditImmutability(db)
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
    }
}