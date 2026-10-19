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

@Entity(tableName = "emergency_cases", indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["triageLevel", "createdAt"])])
data class EmergencyCaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val caseNumber: String, val patientName: String,
    val arrivalTime: String, val triageLevel: String, val chiefComplaint: String,
    val status: String, val createdAt: Long
)

@Entity(tableName = "icu_stays", indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["bedNumber"])])
data class IcuStayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val patientName: String, val bedNumber: String,
    val attendingDoctor: String, val careLevel: String, val admissionDate: String,
    val teamNotes: String, val createdAt: Long
)

@Entity(tableName = "inventory_items", indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["sku"])])
data class InventoryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val name: String, val sku: String,
    val quantity: Double, val reorderLevel: Double, val unit: String,
    val notes: String, val createdAt: Long
)

@Entity(tableName = "lab_orders", indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["status", "createdAt"])])
data class LabOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val patientName: String, val testName: String,
    val status: String, val result: String, val createdAt: Long
)

@Entity(tableName = "radiology_orders", indices = [Index(value = ["sourceRecordId"], unique = true), Index(value = ["status", "createdAt"])])
data class RadiologyOrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceRecordId: Long, val patientName: String, val imagingType: String,
    val requestingDoctor: String, val status: String, val report: String, val createdAt: Long
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

@Dao
interface HospitalDao {
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

    @Query("DELETE FROM emergency_cases WHERE sourceRecordId = :id") suspend fun deleteEmergencyCase(id: Long)
    @Query("DELETE FROM icu_stays WHERE sourceRecordId = :id") suspend fun deleteIcuStay(id: Long)
    @Query("DELETE FROM inventory_items WHERE sourceRecordId = :id") suspend fun deleteInventoryItem(id: Long)
    @Query("DELETE FROM lab_orders WHERE sourceRecordId = :id") suspend fun deleteLabOrder(id: Long)
    @Query("DELETE FROM radiology_orders WHERE sourceRecordId = :id") suspend fun deleteRadiologyOrder(id: Long)
    @Query("DELETE FROM maintenance_tickets WHERE sourceRecordId = :id") suspend fun deleteMaintenanceTicket(id: Long)
    @Query("DELETE FROM vaccination_records WHERE sourceRecordId = :id") suspend fun deleteVaccinationRecord(id: Long)

    /**
     * Keep the legacy searchable timeline and the typed domain table in sync.
     * The original user-entered values remain in hospital_records for backward
     * compatibility; selected core modules also get queryable structured rows.
     */
    @Transaction
    suspend fun saveRecord(record: HospitalRecord) {
        val sourceRecordId = insert(record)
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
            "emergency" -> saveEmergencyCase(EmergencyCaseEntity(
                sourceRecordId = sourceRecordId, caseNumber = record.title,
                patientName = field("اسم المريض"), arrivalTime = field("وقت الوصول"),
                triageLevel = field("درجة الفرز"), chiefComplaint = field("الشكوى الرئيسية"),
                status = field("حالة الحالة"), createdAt = record.createdAt
            ))
            "icu" -> saveIcuStay(IcuStayEntity(
                sourceRecordId = sourceRecordId, patientName = field("اسم المريض"),
                bedNumber = field("رقم السرير"), attendingDoctor = field("الطبيب المسؤول"),
                careLevel = field("مستوى الرعاية"), admissionDate = field("تاريخ الدخول"),
                teamNotes = field("ملاحظات الفريق"), createdAt = record.createdAt
            ))
            "inventory" -> saveInventoryItem(InventoryItemEntity(
                sourceRecordId = sourceRecordId, name = field("اسم الصنف").ifBlank { record.title },
                sku = field("رمز الصنف").ifBlank { "ITEM-" + sourceRecordId },
                quantity = field("الكمية الحالية").toDoubleOrNull() ?: 0.0,
                reorderLevel = field("حد إعادة الطلب").toDoubleOrNull() ?: 0.0,
                unit = field("وحدة القياس"), notes = field("ملاحظات"), createdAt = record.createdAt
            ))
            "lab" -> saveLabOrder(LabOrderEntity(
                sourceRecordId = sourceRecordId, patientName = field("اسم المريض"),
                testName = field("اسم الفحص"), status = field("حالة الفحص"),
                result = field("النتيجة"), createdAt = record.createdAt
            ))
            "radiology" -> saveRadiologyOrder(RadiologyOrderEntity(
                sourceRecordId = sourceRecordId, patientName = field("اسم المريض"),
                imagingType = field("نوع التصوير"), requestingDoctor = field("الطبيب الطالب"),
                status = field("الحالة"), report = field("التقرير"), createdAt = record.createdAt
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

    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    fun invoices(): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM payments ORDER BY createdAt DESC")
    fun payments(): Flow<List<PaymentEntity>>

    @Query("SELECT COUNT(*) FROM hospital_records WHERE category = :category")
    suspend fun count(category: String): Int

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
            "vaccination" -> deleteVaccinationRecord(id)
            "emergency" -> deleteEmergencyCase(id)
            "icu" -> deleteIcuStay(id)
            "inventory" -> deleteInventoryItem(id)
            "lab" -> deleteLabOrder(id)
            "radiology" -> deleteRadiologyOrder(id)
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
        MaintenanceTicketEntity::class,
        PatientEntity::class,
        DoctorEntity::class,
        AppointmentEntity::class,
        InvoiceEntity::class,
        PaymentEntity::class,
        BusinessProfileEntity::class,
        VaccinationRecordEntity::class
    ],
    version = 7,
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

        fun get(context: Context): HospitalDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HospitalDatabase::class.java,
                    "hospital-local.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
