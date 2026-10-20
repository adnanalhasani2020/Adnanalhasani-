package com.adnanalhasani.hospital

import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Offline equivalents of the server's transactional workflows.
 *
 * Room continues to own the legacy searchable records. These tables keep the
 * operational invariants that must also hold when the device is offline.
 */
internal class LocalWorkflowStore(private val database: HospitalDatabase) {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun fields(record: HospitalRecord): Map<String, String> =
        record.details.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) null
            else line.substring(0, separator).trim() to line.substring(separator + 2).trim()
        }.toMap()

    private fun Map<String, String>.field(vararg names: String): String =
        names.firstNotNullOfOrNull { this[it]?.takeIf(String::isNotBlank) }.orEmpty()

    private fun canonicalDateTime(raw: String): String {
        val value = raw.trim().replace('T', ' ')
        val parsed = runCatching { LocalDateTime.parse(value, timestampFormatter) }.getOrNull()
            ?: throw IllegalArgumentException("أدخل التاريخ والوقت بصيغة YYYY-MM-DD HH:mm.")
        return parsed.format(timestampFormatter)
    }

    private fun SupportSQLiteDatabase.string(sql: String, vararg args: Any?): String? =
        query(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun SupportSQLiteDatabase.long(sql: String, vararg args: Any?): Long? =
        query(sql, args).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    private fun SupportSQLiteDatabase.int(sql: String, vararg args: Any?): Int? =
        query(sql, args).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null }

    /** Check user input and current local state before writing the generic record. */
    fun validate(record: HospitalRecord, existingId: Long? = null) {
        val f = fields(record)
        val db = database.openHelper.readableDatabase
        when (record.category) {
            "pharmacy" -> {
                require(f.field("اسم الدواء").isNotBlank()) { "اسم الدواء مطلوب." }
                require(f.field("رمز الدواء").isNotBlank()) { "رمز الدواء مطلوب." }
                val qty = f.field("الكمية").toIntOrNull()
                require(qty != null && qty >= 0) { "كمية المخزون يجب أن تكون عدداً صحيحاً غير سالب." }
            }
            "beds" -> {
                require(f.field("الجناح").isNotBlank() && f.field("رقم السرير").isNotBlank()) {
                    "اسم الجناح ورقم السرير مطلوبان."
                }
            }
            "admissions" -> {
                require(f.field("اسم المريض").isNotBlank()) { "اسم المريض مطلوب للتنويم." }
                require(f.field("القسم / الجناح").isNotBlank() && f.field("رقم السرير").isNotBlank()) {
                    "حدد الجناح ورقم السرير قبل تسجيل التنويم."
                }
                require(f.field("التاريخ").isNotBlank()) { "تاريخ الدخول أو الخروج مطلوب." }
                val bedId = db.long(
                    "SELECT id FROM local_beds WHERE ward=? AND bedNo=? AND active=1 LIMIT 1",
                    f.field("القسم / الجناح"), f.field("رقم السرير")
                )
                require(bedId != null) { "السرير غير مسجل أو غير متاح. سجّل السرير أولاً من شاشة إدارة الأسرة." }
                val status = if (f.field("حالة التنويم").contains("خرج")) "discharged" else "admitted"
                if (status == "admitted") {
                    val occupied = db.long(
                        """SELECT id FROM local_admissions WHERE status='admitted'
                           AND ward=? AND bedNo=? AND sourceRecordId<>COALESCE(?, -1) LIMIT 1""",
                        f.field("القسم / الجناح"), f.field("رقم السرير"), existingId
                    )
                    require(occupied == null) { "السرير مشغول بمريض آخر." }
                    val patientAdmitted = db.long(
                        """SELECT id FROM local_admissions WHERE status='admitted'
                           AND patientName=? COLLATE NOCASE AND sourceRecordId<>COALESCE(?, -1) LIMIT 1""",
                        f.field("اسم المريض"), existingId
                    )
                    require(patientAdmitted == null) { "المريض لديه عملية تنويم نشطة بالفعل." }
                }
            }
            "appointments" -> {
                val patient = f.field("اسم المريض")
                val doctor = f.field("اسم الطبيب")
                require(patient.isNotBlank() && doctor.isNotBlank()) { "اسم المريض والطبيب مطلوبان للموعد." }
                val start = canonicalDateTime(f.field("التاريخ والوقت"))
                val end = canonicalDateTime(f.field("وقت الانتهاء"))
                require(end > start) { "وقت انتهاء الموعد يجب أن يأتي بعد وقت بدايته." }
                val doctorOverlap = db.long(
                    """SELECT id FROM local_appointments WHERE doctorName=? COLLATE NOCASE
                       AND status IN ('scheduled','checked_in') AND startsAt<? AND endsAt>?
                       AND sourceRecordId<>COALESCE(?, -1) LIMIT 1""",
                    doctor, end, start, existingId
                )
                require(doctorOverlap == null) { "لدى الطبيب موعد آخر يتداخل مع هذا الوقت." }
                val patientOverlap = db.long(
                    """SELECT id FROM local_appointments WHERE patientName=? COLLATE NOCASE
                       AND status IN ('scheduled','checked_in') AND startsAt<? AND endsAt>?
                       AND sourceRecordId<>COALESCE(?, -1) LIMIT 1""",
                    patient, end, start, existingId
                )
                require(patientOverlap == null) { "لدى المريض موعد آخر يتداخل مع هذا الوقت." }
            }
            "prescriptions" -> {
                val status = f.field("الحالة")
                if (status.contains("صرف") || status.equals("dispensed", true)) {
                    val qty = f.field("الكمية المطلوبة", "الكمية المصروفة").toIntOrNull()
                    require(qty != null && qty > 0) { "أدخل كمية صحيحة أكبر من صفر قبل صرف الوصفة." }
                    val medId = db.long(
                        "SELECT id FROM local_medicines WHERE name=? COLLATE NOCASE AND active=1 LIMIT 1",
                        f.field("اسم الدواء")
                    )
                    require(medId != null) { "الدواء غير موجود في مخزون الصيدلية." }
                    val available = db.int("SELECT quantity FROM local_medicines WHERE id=?", medId) ?: 0
                    val alreadyDispensed = db.string(
                        "SELECT status FROM local_prescriptions WHERE sourceRecordId=?",
                        existingId ?: -1L
                    ) == "dispensed"
                    if (!alreadyDispensed) require(available >= qty) {
                        "المخزون غير كافٍ. المتاح: $available."
                    }
                }
            }
            "lab" -> {
                require(f.field("اسم المريض").isNotBlank() && f.field("اسم الفحص").isNotBlank()) {
                    "اسم المريض والفحص مطلوبان."
                }
                val status = canonicalLabStatus(f.field("حالة الفحص"))
                require(status != "resulted" || f.field("النتيجة").isNotBlank()) {
                    "أدخل نتيجة الفحص قبل تحويل الحالة إلى مكتمل."
                }
            }
        }
    }

    /** Persist operational rows and append-only timelines after the legacy row is created. */
    fun sync(record: HospitalRecord, sourceRecordId: Long) {
        val f = fields(record)
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            val now = LocalDateTime.now().format(timestampFormatter)
            when (record.category) {
                "pharmacy" -> {
                    val sku = f.field("رمز الدواء")
                    val name = f.field("اسم الدواء")
                    val addQty = f.field("الكمية").toIntOrNull() ?: 0
                    val existingMovement = db.long(
                        "SELECT id FROM local_medicine_movements WHERE sourceRecordId=?", sourceRecordId
                    )
                    if (existingMovement == null) {
                        var medicineId = db.long("SELECT id FROM local_medicines WHERE sku=?", sku)
                        if (medicineId == null) {
                            db.execSQL(
                                """INSERT INTO local_medicines(sourceRecordId,sku,name,quantity,reorderLevel,unit,active,updatedAt)
                                   VALUES(?,?,?,?,?,?,1,?)""",
                                arrayOf(sourceRecordId, sku, name, addQty,
                                    f.field("حد إعادة الطلب").toIntOrNull() ?: 0,
                                    f.field("وحدة القياس").ifBlank { "unit" }, now)
                            )
                            medicineId = db.long("SELECT id FROM local_medicines WHERE sku=?", sku)
                                ?: error("تعذر إنشاء صنف الدواء.")
                            if (addQty > 0) db.execSQL(
                                """INSERT INTO local_medicine_movements(sourceRecordId,medicineId,movementType,quantityDelta,balanceAfter,reference,occurredAt)
                                   VALUES(?,?, 'initial_stock', ?, ?, ?, ?)""",
                                arrayOf(sourceRecordId, medicineId, addQty, addQty, record.title, now)
                            )
                        } else if (addQty > 0) {
                            val current = db.int("SELECT quantity FROM local_medicines WHERE id=?", medicineId) ?: 0
                            val next = Math.addExact(current, addQty)
                            db.execSQL("UPDATE local_medicines SET quantity=?,name=?,updatedAt=? WHERE id=?",
                                arrayOf(next, name, now, medicineId))
                            db.execSQL(
                                """INSERT INTO local_medicine_movements(sourceRecordId,medicineId,movementType,quantityDelta,balanceAfter,reference,occurredAt)
                                   VALUES(?,?, 'restock', ?, ?, ?, ?)""",
                                arrayOf(sourceRecordId, medicineId, addQty, next, record.title, now)
                            )
                        }
                    }
                }
                "beds" -> {
                    val ward = f.field("الجناح")
                    val bedNo = f.field("رقم السرير")
                    val status = f.field("الحالة")
                    val active = if (status.contains("صيانة") || status.contains("غير نشط")) 0 else 1
                    val oldId = db.long("SELECT id FROM local_beds WHERE sourceRecordId=?", sourceRecordId)
                    if (oldId != null) {
                        db.execSQL("UPDATE local_beds SET ward=?,bedNo=?,active=?,notes=? WHERE id=?",
                            arrayOf(ward, bedNo, active, f.field("ملاحظات"), oldId))
                    } else {
                        val sameBed = db.long("SELECT id FROM local_beds WHERE ward=? AND bedNo=?", ward, bedNo)
                        if (sameBed != null) {
                            db.execSQL("UPDATE local_beds SET active=?,notes=? WHERE id=?",
                                arrayOf(active, f.field("ملاحظات"), sameBed))
                        } else {
                            db.execSQL("INSERT INTO local_beds(sourceRecordId,ward,bedNo,active,notes) VALUES(?,?,?,?,?)",
                                arrayOf(sourceRecordId, ward, bedNo, active, f.field("ملاحظات")))
                        }
                    }
                }
                "admissions" -> {
                    val patient = f.field("اسم المريض")
                    val ward = f.field("القسم / الجناح")
                    val bedNo = f.field("رقم السرير")
                    val date = f.field("التاريخ")
                    val discharged = f.field("حالة التنويم").contains("خرج")
                    val oldAdmittedAt = db.string("SELECT admittedAt FROM local_admissions WHERE sourceRecordId=?", sourceRecordId)
                    val admittedAt = oldAdmittedAt ?: date
                    val dischargeAt = if (discharged) date else null
                    val status = if (discharged) "discharged" else "admitted"
                    val reason = f.field("سبب الدخول", "سبب التنويم")
                    val existing = db.long("SELECT id FROM local_admissions WHERE sourceRecordId=?", sourceRecordId)
                    if (existing != null) {
                        db.execSQL("UPDATE local_admissions SET patientName=?,ward=?,bedNo=?,admittedAt=?,dischargedAt=?,reason=?,status=? WHERE id=?",
                            arrayOf(patient, ward, bedNo, admittedAt, dischargeAt, reason, status, existing))
                    } else {
                        db.execSQL("INSERT INTO local_admissions(sourceRecordId,patientName,ward,bedNo,admittedAt,dischargedAt,reason,status) VALUES(?,?,?,?,?,?,?,?)",
                            arrayOf(sourceRecordId, patient, ward, bedNo, admittedAt, dischargeAt, reason, status))
                    }
                }
                "appointments" -> {
                    val start = canonicalDateTime(f.field("التاريخ والوقت"))
                    val end = canonicalDateTime(f.field("وقت الانتهاء"))
                    db.execSQL("DELETE FROM local_appointments WHERE sourceRecordId=?", arrayOf(sourceRecordId))
                    db.execSQL(
                        """INSERT INTO local_appointments(sourceRecordId,patientName,doctorName,startsAt,endsAt,status)
                           VALUES(?,?,?,?,?,'scheduled')""",
                        arrayOf(sourceRecordId, f.field("اسم المريض"), f.field("اسم الطبيب"), start, end)
                    )
                }
                "prescriptions" -> {
                    val medication = f.field("اسم الدواء")
                    val qty = f.field("الكمية المطلوبة", "الكمية المصروفة").toIntOrNull() ?: 0
                    val enteredStatus = f.field("الحالة")
                    val status = when {
                        enteredStatus.contains("صرف") || enteredStatus.equals("dispensed", true) -> "dispensed"
                        enteredStatus.contains("لغ") || enteredStatus.equals("cancelled", true) -> "cancelled"
                        else -> "pending"
                    }
                    val existingStatus = db.string("SELECT status FROM local_prescriptions WHERE sourceRecordId=?", sourceRecordId)
                    val medId = db.long("SELECT id FROM local_medicines WHERE name=? COLLATE NOCASE AND active=1 LIMIT 1", medication)
                    if (status == "dispensed" && existingStatus != "dispensed") {
                        val medicineId = medId ?: error("الدواء غير موجود في مخزون الصيدلية.")
                        val available = db.int("SELECT quantity FROM local_medicines WHERE id=?", medicineId) ?: 0
                        require(available >= qty && qty > 0) { "المخزون غير كافٍ لصرف الوصفة." }
                        val next = available - qty
                        db.execSQL("UPDATE local_medicines SET quantity=?,updatedAt=? WHERE id=?", arrayOf(next, now, medicineId))
                        db.execSQL(
                            """INSERT INTO local_medicine_movements(sourceRecordId,medicineId,movementType,quantityDelta,balanceAfter,reference,occurredAt)
                               VALUES(?,?,'dispense',?,?,?,?)""",
                            arrayOf(sourceRecordId, medicineId, -qty, next, record.title, now)
                        )
                    }
                    val dispensedAt = if (status == "dispensed") (db.string("SELECT dispensedAt FROM local_prescriptions WHERE sourceRecordId=?", sourceRecordId) ?: now) else null
                    if (existingStatus == null) {
                        db.execSQL(
                            """INSERT INTO local_prescriptions(sourceRecordId,medicineId,medicationName,quantity,status,dispensedAt)
                               VALUES(?,?,?,?,?,?)""",
                            arrayOf(sourceRecordId, medId, medication, qty, status, dispensedAt)
                        )
                    } else {
                        db.execSQL(
                            """UPDATE local_prescriptions SET medicineId=?,medicationName=?,quantity=?,status=?,dispensedAt=?
                               WHERE sourceRecordId=?""",
                            arrayOf(medId, medication, qty, status, dispensedAt, sourceRecordId)
                        )
                    }
                }
                "lab" -> {
                    val status = canonicalLabStatus(f.field("حالة الفحص"))
                    val result = f.field("النتيجة")
                    val previous = db.query(
                        "SELECT status,result FROM local_lab_order_events WHERE sourceRecordId=? ORDER BY id DESC LIMIT 1",
                        arrayOf(sourceRecordId)
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
                    }
                    if (previous == null || previous.first != status || previous.second != result) {
                        db.execSQL(
                            "INSERT INTO local_lab_order_events(sourceRecordId,status,result,occurredAt) VALUES(?,?,?,?)",
                            arrayOf(sourceRecordId, status, result, now)
                        )
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun rows(category: String): List<Pair<String, String>> {
        val db = database.openHelper.readableDatabase
        val sql = when (category) {
            "pharmacy" -> """SELECT name || ' (' || sku || ')',
                'المتاح: ' || quantity || ' • حد إعادة الطلب: ' || reorderLevel || ' • ' || unit
                FROM local_medicines ORDER BY name COLLATE NOCASE"""
            "beds" -> """SELECT b.ward || ' • سرير ' || b.bedNo,
                CASE WHEN b.active=0 THEN 'غير متاح'
                     WHEN EXISTS(SELECT 1 FROM local_admissions a WHERE a.ward=b.ward AND a.bedNo=b.bedNo AND a.status='admitted')
                     THEN 'مشغول' ELSE 'متاح' END || CASE WHEN b.notes='' THEN '' ELSE ' • ' || b.notes END
                FROM local_beds b ORDER BY b.ward, b.bedNo"""
            "admissions" -> """SELECT patientName || ' • ' || ward || ' / ' || bedNo,
                status || ' • دخول: ' || admittedAt || CASE WHEN dischargedAt IS NULL THEN '' ELSE ' • خروج: ' || dischargedAt END
                FROM local_admissions ORDER BY id DESC"""
            "appointments" -> """SELECT patientName || ' • ' || doctorName, startsAt || ' — ' || endsAt
                FROM local_appointments ORDER BY startsAt DESC"""
            "lab" -> """SELECT h.title, e.status || ' • ' || e.occurredAt ||
                CASE WHEN e.result='' THEN '' ELSE ' • ' || e.result END
                FROM local_lab_order_events e INNER JOIN hospital_records h ON h.id=e.sourceRecordId
                WHERE e.id IN (SELECT MAX(id) FROM local_lab_order_events GROUP BY sourceRecordId)
                ORDER BY e.id DESC"""
            else -> return emptyList()
        }
        return db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty())
            }
        }
    }

    private fun canonicalLabStatus(raw: String): String = when {
        raw.contains("جمع") || raw.contains("collected", true) -> "collected"
        raw.contains("تنفيذ") || raw.contains("processing", true) -> "processing"
        raw.contains("مكتمل") || raw.contains("نتيج") || raw.contains("resulted", true) -> "resulted"
        raw.contains("لغ") || raw.contains("cancel", true) -> "cancelled"
        else -> "ordered"
    }
}
