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
    suspend fun insert(record: HospitalRecord)

    /**
     * Keep the legacy searchable timeline and the typed domain table in sync.
     * The original user-entered values remain in hospital_records for backward
     * compatibility; selected core modules also get queryable structured rows.
     */
    @Transaction
    suspend fun saveRecord(record: HospitalRecord) {
        insert(record)
        val fields = record.details.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) null
            else line.substring(0, separator).trim() to line.substring(separator + 2).trim()
        }.toMap()
        fun field(vararg names: String): String =
            names.firstNotNullOfOrNull { name -> fields[name]?.takeIf { it.isNotBlank() } }.orEmpty()

        when (record.category) {
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
                    amountMinor = field("المبلغ").toBigDecimalOrNull()
                        ?.movePointRight(2)?.toLong() ?: 0L,
                    currency = field("العملة").ifBlank { "YER" },
                    status = field("الحالة").ifBlank { "open" },
                    createdAt = record.createdAt
                )
            )
            "payments" -> insertPayment(
                PaymentEntity(
                    invoiceNumber = field("رقم الفاتورة").ifBlank { record.title },
                    invoiceId = findInvoiceIdByNumber(field("رقم الفاتورة").ifBlank { record.title }),
                    amountMinor = field("المبلغ المدفوع").toBigDecimalOrNull()
                        ?.movePointRight(2)?.toLong() ?: 0L,
                    method = field("طريقة الدفع"),
                    receiptReference = field("مرجع الإيصال"),
                    createdAt = record.createdAt
                )
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPatient(patient: PatientEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDoctor(doctor: DoctorEntity)

    @Insert
    suspend fun insertAppointment(appointment: AppointmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoice(invoice: InvoiceEntity)

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

    @Query("DELETE FROM hospital_records WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        HospitalRecord::class,
        PatientEntity::class,
        DoctorEntity::class,
        AppointmentEntity::class,
        InvoiceEntity::class,
        PaymentEntity::class
    ],
    version = 3,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
