package com.adnanalhasani.hospital

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith


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
    version = 14,
    exportSchema = false
)
abstract class HospitalDatabaseV14Fixture : RoomDatabase()

@RunWith(AndroidJUnit4::class)
class HospitalDatabaseRegressionTest {
    private var roomDatabase: HospitalDatabase? = null

    @After
    fun closeDatabase() {
        roomDatabase?.close()
        roomDatabase = null
    }

    @Test
    fun v14RoomDatabaseMigratesToV16WithoutLosingRowsOrAllowingEventEdits() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureName = "hospital-v14-fixture-${System.nanoTime()}.db"
        val legacy = Room.databaseBuilder(context, HospitalDatabaseV14Fixture::class.java, fixtureName)
            .allowMainThreadQueries()
            .build()

        try {
            val oldDb = legacy.openHelper.writableDatabase
            oldDb.execSQL(
                "INSERT INTO hospital_records(id, category, title, details, createdAt) " +
                    "VALUES (41, 'patients', 'PT-41', 'existing patient record', 1234)"
            )
            oldDb.execSQL(
                "INSERT INTO patients(id, fileNumber, fullName, phone, age, notes, createdAt) " +
                    "VALUES (1, 'PT-41', 'Existing Patient', '700', '42', 'preserve me', 1234)"
            )
            oldDb.execSQL(
                "INSERT INTO lab_orders(id, sourceRecordId, patientName, testName, status, result, createdAt, patientId, clinicalVisitId) " +
                    "VALUES (7, 41, 'Existing Patient', 'CBC', 'requested', '', 1234, 1, NULL)"
            )
        } finally {
            legacy.close()
        }

        try {
            val migrated = Room.databaseBuilder(context, HospitalDatabase::class.java, fixtureName)
                .addMigrations(HospitalDatabase.MIGRATION_14_15, HospitalDatabase.MIGRATION_15_16)
                .allowMainThreadQueries()
                .build()
            roomDatabase = migrated
            val db = migrated.openHelper.writableDatabase

            // Opening Room v16 validates the complete schema after both migrations.
            assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM hospital_records WHERE id = 41 AND title = 'PT-41'"))
            assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM patients WHERE id = 1 AND fileNumber = 'PT-41' AND notes = 'preserve me'"))
            assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM lab_orders WHERE id = 7 AND testName = 'CBC' AND patientId = 1"))
            assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM lab_workflow_settings WHERE `key` = 'require_receipt_confirmation'"))

            db.execSQL(
                "INSERT INTO lab_specimens(id, labOrderId, specimenCode, specimenType, status, collectedAt, receivedAt, rejectionReason, updatedAt) " +
                    "VALUES (12, 7, 'SP-12', 'blood', 'collected', 1234, NULL, '', 1234)"
            )
            db.execSQL(
                "INSERT INTO lab_specimen_events(id, specimenId, status, note, actor, occurredAt) " +
                    "VALUES (19, 12, 'collected', 'registered', 'tech-1', 1234)"
            )

            var updateRejected = false
            try {
                db.execSQL("UPDATE lab_specimen_events SET note = 'tampered' WHERE id = 19")
            } catch (_: SQLiteException) {
                updateRejected = true
            }
            assertTrue("Specimen event history must reject updates", updateRejected)
            assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM lab_specimen_events WHERE id = 19 AND note = 'registered'"))
        } finally {
            roomDatabase?.close()
            roomDatabase = null
            context.deleteDatabase(fixtureName)
        }
    }

    @Test
    fun receptionBundleRollsBackAllWritesWhenVisitLinkFails() = runBlocking {
        val dao = database().hospitalDao()
        val patient = HospitalRecord(
            category = "patients",
            title = "PT-ROLLBACK",
            details = "اسم المريض: مريض اختبار\nرقم الهاتف: 777\nالعمر: 30\nملاحظات: اختبار"
        )
        val visit = HospitalRecord(
            category = "encounters",
            title = "مريض اختبار",
            details = "اسم المريض: مريض اختبار\nاسم الطبيب: طبيب غير موجود\nالشكوى الرئيسية: اختبار\nالتشخيص: اختبار\nملاحظات: اختبار\nتاريخ الزيارة: 2026-10-21 10:00"
        )
        val invoice = HospitalRecord(
            category = "invoices",
            title = "INV-ROLLBACK",
            details = "اسم المريض: مريض اختبار\nالمبلغ: 100.00\nالعملة: YER\nالحالة: open"
        )
        val payment = HospitalRecord(
            category = "payments",
            title = "INV-ROLLBACK",
            details = "رقم الفاتورة: INV-ROLLBACK\nالمبلغ المدفوع: 20.00\nطريقة الدفع: cash\nمرجع الإيصال: RCPT-ROLLBACK"
        )

        var failed = false
        try {
            dao.saveReceptionBundle(patient, visit, invoice, payment, doctorId = 987654321L)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("A broken doctor foreign key should fail the reception transaction", failed)
        val db = requireNotNull(roomDatabase)
        assertEquals(0, scalarCount(db.openHelper.writableDatabase, "SELECT COUNT(*) FROM hospital_records"))
        assertEquals(0, scalarCount(db.openHelper.writableDatabase, "SELECT COUNT(*) FROM patients"))
        assertEquals(0, scalarCount(db.openHelper.writableDatabase, "SELECT COUNT(*) FROM clinical_visits"))
        assertEquals(0, scalarCount(db.openHelper.writableDatabase, "SELECT COUNT(*) FROM invoices"))
        assertEquals(0, scalarCount(db.openHelper.writableDatabase, "SELECT COUNT(*) FROM payments"))
    }

    @Test
    fun duplicateInvoiceDoesNotCreateAnotherReceptionBundle() = runBlocking {
        val dao = database().hospitalDao()
        dao.saveReceptionBundle(
            patientRecord = HospitalRecord(
                category = "patients",
                title = "PT-FIRST",
                details = "اسم المريض: المريض الأول\nرقم الهاتف: 700"
            ),
            visitRecord = HospitalRecord(
                category = "encounters",
                title = "المريض الأول",
                details = "اسم المريض: المريض الأول\nاسم الطبيب: \nالشكوى الرئيسية: فحص\nالتشخيص: \nملاحظات: \nتاريخ الزيارة: 2026-10-21 10:00"
            ),
            invoiceRecord = HospitalRecord(
                category = "invoices",
                title = "INV-DUP",
                details = "اسم المريض: المريض الأول\nالمبلغ: 50.00\nالعملة: YER\nالحالة: open"
            ),
            paymentRecord = null,
            doctorId = null
        )

        var duplicateRejected = false
        try {
            dao.saveReceptionBundle(
                patientRecord = HospitalRecord(
                    category = "patients",
                    title = "PT-SECOND",
                    details = "اسم المريض: المريض الثاني\nرقم الهاتف: 701"
                ),
                visitRecord = HospitalRecord(
                    category = "encounters",
                    title = "المريض الثاني",
                    details = "اسم المريض: المريض الثاني\nاسم الطبيب: \nالشكوى الرئيسية: فحص\nالتشخيص: \nملاحظات: \nتاريخ الزيارة: 2026-10-21 11:00"
                ),
                invoiceRecord = HospitalRecord(
                    category = "invoices",
                    title = " INV-DUP ",
                    details = "اسم المريض: المريض الثاني\nالمبلغ: 60.00\nالعملة: YER\nالحالة: open"
                ),
                paymentRecord = null,
                doctorId = null
            )
        } catch (_: IllegalStateException) {
            duplicateRejected = true
        }
        assertTrue("Duplicate normalized invoice numbers must be rejected", duplicateRejected)
        val db = requireNotNull(roomDatabase).openHelper.writableDatabase
        assertEquals(3, scalarCount(db, "SELECT COUNT(*) FROM hospital_records"))
        assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM patients"))
        assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM clinical_visits"))
        assertEquals(1, scalarCount(db, "SELECT COUNT(*) FROM invoices"))
    }

    private fun database(): HospitalDatabase {
        return Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HospitalDatabase::class.java
        ).allowMainThreadQueries().build().also { roomDatabase = it }
    }

    private fun scalarCount(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
}
