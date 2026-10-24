package com.adnanalhasani.hospital

import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Offline equivalents of the server's transactional workflows.
 *
 * Room continues to own the legacy searchable records. These tables keep the
 * operational invariants that must also hold when the device is offline.
 */
internal class LocalWorkflowStore(private val database: HospitalDatabase) {
    private val timestampFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")

    private fun fields(record: HospitalRecord): Map<String, String> =
        record.details.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) null
            else line.substring(0, separator).trim() to line.substring(separator + 2).trim()
        }.toMap()

    private fun Map<String, String>.field(vararg names: String): String =
        names.firstNotNullOfOrNull { this[it]?.takeIf(String::isNotBlank) }.orEmpty()

    private fun canonicalAdmissionDate(raw: String): String {
        val value = raw.trim().replace('T', ' ')
        val dateOnly = runCatching { LocalDate.parse(value) }.getOrNull()
        if (dateOnly != null) return dateOnly.atStartOfDay().format(timestampFormatter)
        return canonicalDateTime(value)
    }

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

    private fun jsonEscape(raw: String): String = escapeJsonString(raw)

    private fun appendAudit(
        db: SupportSQLiteDatabase, action: String, entityType: String, entityId: String, detailsJson: String, occurredAt: String
    ) {
        db.execSQL(
            "INSERT INTO local_audit_log(actor,action,entityType,entityId,detailsJson,occurredAt) VALUES(?,?,?,?,?,?)",
            arrayOf("local-user", action, entityType, entityId, detailsJson, occurredAt)
        )
    }

    private fun queueFinanceEvent(db: SupportSQLiteDatabase, record: HospitalRecord, sourceRecordId: Long, occurredAt: String) {
        val event = when (record.category) {
            "invoices" -> Triple("hospital.invoice.created", "invoice", "invoice-created:$sourceRecordId")
            "payments" -> Triple("hospital.payment.received", "payment", "payment-received:$sourceRecordId")
            else -> return
        }
        val payload = """{"source_record_id":$sourceRecordId,"category":"${jsonEscape(record.category)}","title":"${jsonEscape(record.title)}","details":"${jsonEscape(record.details)}","occurred_at":"$occurredAt"}"""
        db.execSQL(
            """INSERT OR IGNORE INTO local_finance_outbox
               (eventKey,eventType,aggregateType,aggregateId,payloadJson,status,attempts,createdAt,updatedAt)
               VALUES(?,?,?,?,?,'pending',0,?,?)""",
            arrayOf(event.third, event.first, event.second, sourceRecordId.toString(), payload, occurredAt, occurredAt)
        )
    }
    /** Check user input and current local state before writing the generic record. */
    fun validate(record: HospitalRecord, existingId: Long? = null) {
        val f = fields(record)
        val db = database.openHelper.readableDatabase
        when (record.category) {
            "users" -> {
                val username = f.field("اسم المستخدم")
                require(username.isNotBlank()) { "اسم المستخدم مطلوب." }
                require(canonicalUserRole(f.field("الدور")) != null) {
                    "اختر دوراً معتمداً: مدير، طبيب، ممرض، استقبال، مختبر، صيدلي أو محاسب."
                }
                val duplicate = db.long(
                    "SELECT id FROM local_users WHERE username=? COLLATE NOCASE AND sourceRecordId<>COALESCE(?, -1) LIMIT 1",
                    username, existingId
                )
                require(duplicate == null) { "اسم المستخدم مستخدم بالفعل." }
            }
            "encounters" -> {
                require(f.field("اسم المريض").isNotBlank() && f.field("الشكوى الرئيسية").isNotBlank()) {
                    "اسم المريض والشكوى الرئيسية مطلوبان للزيارة."
                }
                require(db.long("SELECT id FROM patients WHERE fullName=? COLLATE NOCASE LIMIT 1", f.field("اسم المريض")) != null) {
                    "المريض غير مسجل في قاعدة البيانات المحلية."
                }
                if (f.field("تاريخ الزيارة").isNotBlank()) canonicalDateTime(f.field("تاريخ الزيارة"))
            }
            "pharmacy" -> {
                if (existingId != null) {
                    require(db.long(
                        "SELECT id FROM local_medicine_movements WHERE sourceRecordId=? LIMIT 1", existingId
                    ) == null) {
                        "سجل توريد المخزون أصبح حركة ثابتة؛ لا تعدّل تفاصيله بعد التسجيل. أنشئ توريداً جديداً أو حركة تصحيح."
                    }
                }
                require(f.field("اسم الدواء").isNotBlank()) { "اسم الدواء مطلوب." }
                require(f.field("رمز الدواء").isNotBlank()) { "رمز الدواء مطلوب." }
                val qty = f.field("الكمية").toIntOrNull()
                require(qty != null && qty >= 0) { "كمية المخزون يجب أن تكون عدداً صحيحاً غير سالب." }
                val expiry = f.field("تاريخ الصلاحية")
                if (expiry.isNotBlank()) {
                    val parsed = runCatching { LocalDate.parse(expiry) }.getOrNull()
                    require(parsed != null && parsed.toString() == expiry) { "تاريخ الصلاحية يجب أن يكون بصيغة YYYY-MM-DD." }
                    require(parsed!! >= LocalDate.now()) { "لا يمكن إدخال تشغيلة منتهية الصلاحية." }
                }
                val batchNo = f.field("رقم التشغيلة")
                val existingMedicineId = db.long("SELECT id FROM local_medicines WHERE sku=?", f.field("رمز الدواء"))
                if (existingMedicineId != null && batchNo.isNotBlank() && expiry.isNotBlank()) {
                    val oldExpiry = db.string("SELECT expiryDate FROM local_medicine_batches WHERE medicineId=? AND batchNo=?",
                        existingMedicineId, batchNo)
                    require(oldExpiry == null || oldExpiry == expiry) { "رقم التشغيلة مسجل بتاريخ صلاحية مختلف." }
                }
            }
            "beds" -> {
                val ward = f.field("الجناح")
                val bedNo = f.field("رقم السرير")
                require(ward.isNotBlank() && bedNo.isNotBlank()) {
                    "اسم الجناح ورقم السرير مطلوبان."
                }
                // A bed must have exactly one owning record. Without this guard,
                // sync() could silently update another record's bed instead of
                // creating a distinct bed, leaving the new source record unlinked.
                val duplicateBed = db.long(
                    """SELECT id FROM local_beds
                       WHERE ward=? COLLATE NOCASE AND bedNo=? COLLATE NOCASE
                         AND sourceRecordId<>COALESCE(?, -1) LIMIT 1""",
                    ward, bedNo, existingId
                )
                require(duplicateBed == null) {
                    "رقم السرير مسجل مسبقاً في هذا الجناح؛ افتح سجل السرير الموجود لتعديله."
                }
            }
            "admissions" -> {
                require(f.field("اسم المريض").isNotBlank()) { "اسم المريض مطلوب للتنويم." }
                require(db.long("SELECT id FROM patients WHERE fullName=? COLLATE NOCASE LIMIT 1", f.field("اسم المريض")) != null) {
                    "المريض غير مسجل في قاعدة البيانات المحلية."
                }
                require(f.field("القسم / الجناح").isNotBlank() && f.field("رقم السرير").isNotBlank()) {
                    "حدد الجناح ورقم السرير قبل تسجيل التنويم."
                }
                require(f.field("التاريخ").isNotBlank()) { "تاريخ الدخول أو الخروج مطلوب." }
                val eventDate = canonicalAdmissionDate(f.field("التاريخ"))
                val desiredStatus = if (f.field("حالة التنويم").contains("خرج")) "discharged" else "admitted"
                val currentStatus = existingId?.let { db.string("SELECT status FROM local_admissions WHERE sourceRecordId=?", it) }
                val currentAdmissionDate = existingId?.let { db.string("SELECT admittedAt FROM local_admissions WHERE sourceRecordId=?", it) }
                if (currentStatus == "discharged" && desiredStatus == "admitted") {
                    throw IllegalArgumentException("لا يمكن إعادة فتح تنويم مغلق؛ أنشئ سجل تنويم جديداً.")
                }
                if (desiredStatus == "discharged") {
                    require(currentStatus == "admitted") { "سجّل دخول المريض أولاً قبل تسجيل الخروج." }
                    require(currentAdmissionDate != null && eventDate >= currentAdmissionDate) {
                        "تاريخ الخروج لا يمكن أن يسبق تاريخ الدخول."
                    }
                }
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
                require(db.long("SELECT id FROM patients WHERE fullName=? COLLATE NOCASE LIMIT 1", patient) != null) {
                    "المريض غير مسجل في قاعدة البيانات المحلية."
                }
                require(db.long("SELECT id FROM doctors WHERE fullName=? COLLATE NOCASE LIMIT 1", doctor) != null) {
                    "الطبيب غير مسجل في قاعدة البيانات المحلية."
                }
                val start = canonicalDateTime(f.field("التاريخ والوقت"))
                val end = canonicalDateTime(f.field("وقت الانتهاء"))
                val desiredStatus = canonicalAppointmentStatus(f.field("حالة الموعد"))
                require(end > start) { "وقت انتهاء الموعد يجب أن يأتي بعد وقت بدايته." }
                val currentStatus = existingId?.let { db.string("SELECT status FROM local_appointments WHERE sourceRecordId=?", it) }
                if (currentStatus != null && currentStatus != desiredStatus) {
                    val allowed = when (currentStatus) {
                        "scheduled" -> setOf("checked_in", "cancelled", "no_show")
                        "checked_in" -> setOf("completed", "cancelled")
                        else -> emptySet()
                    }
                    require(desiredStatus in allowed) { "انتقال حالة الموعد غير مسموح من $currentStatus إلى $desiredStatus." }
                }
                if (desiredStatus == "no_show") {
                    require(start <= LocalDateTime.now().format(timestampFormatter)) {
                        "لا يمكن تسجيل عدم الحضور قبل وقت الموعد."
                    }
                }
                if (desiredStatus == "scheduled" || desiredStatus == "checked_in") {
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
            }
            "prescriptions" -> {
                require(db.long("SELECT id FROM patients WHERE fullName=? COLLATE NOCASE LIMIT 1", f.field("اسم المريض")) != null) {
                    "المريض غير مسجل في قاعدة البيانات المحلية."
                }
                val status = f.field("الحالة")
                val desiredDispensed = status.contains("صرف") || status.contains("صُرف") || status.equals("dispensed", true)
                val previousStatus = existingId?.let {
                    db.string("SELECT status FROM local_prescriptions WHERE sourceRecordId=?", it)
                }
                if (previousStatus == "dispensed") {
                    require(desiredDispensed) {
                        "الوصفة المصروفة لا يمكن إرجاعها إلى معلقة أو إلغاؤها دون حركة عكسية للمخزون."
                    }
                    val previousQuantity = db.int(
                        "SELECT quantity FROM local_prescriptions WHERE sourceRecordId=?", existingId!!
                    )
                    val previousMedication = db.string(
                        "SELECT medicationName FROM local_prescriptions WHERE sourceRecordId=?", existingId
                    )
                    require(f.field("الكمية المطلوبة", "الكمية المصروفة").toIntOrNull() == previousQuantity &&
                        f.field("اسم الدواء") == previousMedication) {
                        "لا يمكن تغيير الدواء أو الكمية بعد الصرف؛ أنشئ وصفة جديدة للتعديل."
                    }
                }
                if (desiredDispensed) {
                    val qty = f.field("الكمية المطلوبة", "الكمية المصروفة").toIntOrNull()
                    require(qty != null && qty > 0) { "أدخل كمية صحيحة أكبر من صفر قبل صرف الوصفة." }
                    val medId = db.long(
                        "SELECT id FROM local_medicines WHERE name=? COLLATE NOCASE AND active=1 LIMIT 1",
                        f.field("اسم الدواء")
                    )
                    require(medId != null) { "الدواء غير موجود في مخزون الصيدلية." }
                    val alreadyDispensed = previousStatus == "dispensed"
                    val batchAvailable = db.long(
                        """SELECT COALESCE(SUM(quantity),0) FROM local_medicine_batches
                           WHERE medicineId=? AND quantity>0 AND (expiryDate IS NULL OR expiryDate>=?)""",
                        medId, LocalDate.now().toString()
                    )?.toInt() ?: 0
                    if (!alreadyDispensed) require(batchAvailable >= qty) {
                        "المخزون الصالح للصرف غير كافٍ. المتاح: $batchAvailable."
                    }
                }
            }
            "lab" -> {
                require(f.field("اسم المريض").isNotBlank() && f.field("اسم الفحص").isNotBlank()) {
                    "اسم المريض والفحص مطلوبان."
                }
                require(db.long("SELECT id FROM patients WHERE fullName=? COLLATE NOCASE LIMIT 1", f.field("اسم المريض")) != null) {
                    "المريض غير مسجل في قاعدة البيانات المحلية."
                }
                val status = canonicalLabStatus(f.field("حالة الفحص"))
                require(status != "resulted" || f.field("النتيجة").isNotBlank()) {
                    "أدخل نتيجة الفحص قبل تحويل الحالة إلى مكتمل."
                }
            }
            "invoices" -> {
                require(db.long("SELECT id FROM patients WHERE fullName=? COLLATE NOCASE LIMIT 1", f.field("اسم المريض")) != null) {
                    "المريض غير مسجل في قاعدة البيانات المحلية."
                }
                val amount = HospitalFieldValidation.toMinorUnitsOrNull(f.field("المبلغ"))
                require(amount != null && amount >= 0L) { "قيمة الفاتورة غير صحيحة." }
                val oldNumber = existingId?.let { db.string("SELECT invoiceNumber FROM invoices WHERE invoiceNumber=(SELECT title FROM hospital_records WHERE id=?)", it) }
                val duplicate = db.long("SELECT id FROM invoices WHERE invoiceNumber=? AND invoiceNumber<>COALESCE(?, '') LIMIT 1",
                    record.title, oldNumber)
                require(duplicate == null) { "رقم الفاتورة مستخدم بالفعل." }
            }
            "payments" -> {
                val invoiceNumber = f.field("رقم الفاتورة").ifBlank { record.title }
                val invoiceId = db.long("SELECT id FROM invoices WHERE invoiceNumber=? LIMIT 1", invoiceNumber)
                require(invoiceId != null) { "الفاتورة غير موجودة في قاعدة البيانات المحلية." }
                val invoiceStatus = db.string("SELECT status FROM invoices WHERE id=?", invoiceId).orEmpty()
                require(!invoiceStatus.contains("void", true) && !invoiceStatus.contains("ملغ")) {
                    "لا يمكن الدفع على فاتورة ملغاة."
                }
                val amount = HospitalFieldValidation.toMinorUnitsOrNull(f.field("المبلغ المدفوع"))
                require(amount != null && amount > 0L) { "قيمة الدفعة يجب أن تكون أكبر من صفر." }
                require(f.field("طريقة الدفع").isNotBlank()) { "طريقة الدفع مطلوبة." }
                val invoiceTotal = db.long("SELECT amountMinor FROM invoices WHERE id=?", invoiceId) ?: 0L
                val oldTitle = existingId?.let { db.string("SELECT title FROM hospital_records WHERE id=?", it) }
                val oldPaymentId = if (existingId != null) db.long(
                    "SELECT id FROM payments WHERE invoiceNumber=? AND createdAt=(SELECT createdAt FROM hospital_records WHERE id=?) LIMIT 1",
                    oldTitle ?: invoiceNumber, existingId
                ) else null
                val alreadyPaid = db.long("SELECT COALESCE(SUM(amountMinor),0) FROM payments WHERE invoiceId=? AND id<>COALESCE(?, -1)",
                    invoiceId, oldPaymentId) ?: 0L
                require(amount <= invoiceTotal - alreadyPaid) {
                    "قيمة الدفعة تتجاوز الرصيد المتبقي للفاتورة."
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
                "users" -> {
                    val username = f.field("اسم المستخدم")
                    val role = canonicalUserRole(f.field("الدور")) ?: error("دور المستخدم غير معتمد.")
                    val active = if (f.field("الحالة").contains("غير نشط") || f.field("الحالة").equals("inactive", true)) 0 else 1
                    val existing = db.long("SELECT id FROM local_users WHERE sourceRecordId=?", sourceRecordId)
                    if (existing == null) {
                        db.execSQL("INSERT INTO local_users(sourceRecordId,username,role,active,createdAt) VALUES(?,?,?,?,?)",
                            arrayOf(sourceRecordId, username, role, active, now))
                    } else {
                        db.execSQL("UPDATE local_users SET username=?,role=?,active=? WHERE sourceRecordId=?",
                            arrayOf(username, role, active, sourceRecordId))
                    }
                }
                "encounters" -> {
                    val patient = f.field("اسم المريض")
                    val doctor = f.field("اسم الطبيب")
                    val openedAt = f.field("تاريخ الزيارة").takeIf { it.isNotBlank() }?.let(::canonicalDateTime)
                        ?: db.string("SELECT openedAt FROM local_encounters WHERE sourceRecordId=?", sourceRecordId)
                        ?: now
                    val closed = f.field("حالة الزيارة").contains("مغلق") || f.field("حالة الزيارة").equals("closed", true)
                    val status = if (closed) "closed" else "open"
                    val closedAt = if (closed) (db.string("SELECT closedAt FROM local_encounters WHERE sourceRecordId=?", sourceRecordId) ?: now) else null
                    val existing = db.long("SELECT id FROM local_encounters WHERE sourceRecordId=?", sourceRecordId)
                    if (existing == null) {
                        db.execSQL(
                            """INSERT INTO local_encounters(sourceRecordId,patientName,doctorName,openedAt,closedAt,chiefComplaint,diagnosis,notes,status)
                               VALUES(?,?,?,?,?,?,?,?,?)""",
                            arrayOf(sourceRecordId, patient, doctor, openedAt, closedAt, f.field("الشكوى الرئيسية"),
                                f.field("التشخيص"), f.field("ملاحظات"), status)
                        )
                    } else {
                        db.execSQL(
                            """UPDATE local_encounters SET patientName=?,doctorName=?,openedAt=?,closedAt=?,chiefComplaint=?,diagnosis=?,notes=?,status=?
                               WHERE sourceRecordId=?""",
                            arrayOf(patient, doctor, openedAt, closedAt, f.field("الشكوى الرئيسية"),
                                f.field("التشخيص"), f.field("ملاحظات"), status, sourceRecordId)
                        )
                    }
                }
                "pharmacy" -> {
                    val sku = f.field("رمز الدواء")
                    val name = f.field("اسم الدواء")
                    val addQty = f.field("الكمية").toIntOrNull() ?: 0
                    val existingMovement = db.long(
                        "SELECT id FROM local_medicine_movements WHERE sourceRecordId=?", sourceRecordId
                    )
                    if (existingMovement == null) {
                        var medicineId = db.long("SELECT id FROM local_medicines WHERE sku=?", sku)
                        val isNewMedicine = medicineId == null
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
                        } else if (addQty > 0) {
                            val current = db.int("SELECT quantity FROM local_medicines WHERE id=?", medicineId) ?: 0
                            val next = Math.addExact(current, addQty)
                            db.execSQL("UPDATE local_medicines SET quantity=?,name=?,updatedAt=? WHERE id=?",
                                arrayOf(next, name, now, medicineId))
                        }
                        if (addQty > 0) {
                            val balance = db.int("SELECT quantity FROM local_medicines WHERE id=?", medicineId) ?: addQty
                            db.execSQL(
                                """INSERT INTO local_medicine_movements(sourceRecordId,medicineId,movementType,quantityDelta,balanceAfter,reference,occurredAt)
                                   VALUES(?,?,?,?,?,?,?)""",
                                arrayOf(sourceRecordId, medicineId, if (isNewMedicine) "initial_stock" else "restock",
                                    addQty, balance, f.field("المرجع", "اسم المورد").ifBlank { record.title }, now)
                            )
                            val batchNo = f.field("رقم التشغيلة").ifBlank { "BATCH-$sourceRecordId" }
                            val expiry = f.field("تاريخ الصلاحية").takeIf { it.isNotBlank() }
                            var batchId = db.long(
                                "SELECT id FROM local_medicine_batches WHERE medicineId=? AND batchNo=?",
                                medicineId, batchNo
                            )
                            if (batchId == null) {
                                db.execSQL(
                                    """INSERT INTO local_medicine_batches(medicineId,batchNo,expiryDate,quantity,receivedAt,reference,createdBy)
                                       VALUES(?,?,?,?,?,?,?)""",
                                    arrayOf(medicineId, batchNo, expiry, addQty, now,
                                        f.field("المرجع", "اسم المورد").ifBlank { record.title }, "local-user")
                                )
                                batchId = db.long(
                                    "SELECT id FROM local_medicine_batches WHERE medicineId=? AND batchNo=?",
                                    medicineId, batchNo
                                ) ?: error("تعذر إنشاء تشغيلة الدواء.")
                            } else {
                                val currentBatchQty = db.int("SELECT quantity FROM local_medicine_batches WHERE id=?", batchId) ?: 0
                                val nextBatchQty = Math.addExact(currentBatchQty, addQty)
                                db.execSQL(
                                    "UPDATE local_medicine_batches SET quantity=?,reference=?,expiryDate=COALESCE(expiryDate,?) WHERE id=?",
                                    arrayOf(nextBatchQty, f.field("المرجع", "اسم المورد").ifBlank { record.title }, expiry, batchId)
                                )
                            }
                            val batchBalance = db.int("SELECT quantity FROM local_medicine_batches WHERE id=?", batchId) ?: addQty
                            db.execSQL(
                                """INSERT INTO local_medicine_batch_movements(sourceRecordId,batchId,medicineId,movementType,quantityDelta,balanceAfter,reference,occurredAt)
                                   VALUES(?,?,?,?,?,?,?,?)""",
                                arrayOf(sourceRecordId, batchId, medicineId, if (isNewMedicine) "initial_stock" else "restock",
                                    addQty, batchBalance, f.field("المرجع", "اسم المورد").ifBlank { record.title }, now)
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
                    val date = canonicalAdmissionDate(f.field("التاريخ"))
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
                    val status = canonicalAppointmentStatus(f.field("حالة الموعد"))
                    db.execSQL(
                        """INSERT INTO local_appointments(sourceRecordId,patientName,doctorName,startsAt,endsAt,status)
                           VALUES(?,?,?,?,?,?)""",
                        arrayOf(sourceRecordId, f.field("اسم المريض"), f.field("اسم الطبيب"), start, end, status)
                    )
                }
                "prescriptions" -> {
                    val medication = f.field("اسم الدواء")
                    val qty = f.field("الكمية المطلوبة", "الكمية المصروفة").toIntOrNull() ?: 0
                    val enteredStatus = f.field("الحالة")
                    val status = when {
                        enteredStatus.contains("صرف") || enteredStatus.contains("صُرف") || enteredStatus.equals("dispensed", true) -> "dispensed"
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
                        val eligibleBatches = mutableListOf<Pair<Long, Int>>()
                        db.query(
                            """SELECT id,quantity FROM local_medicine_batches
                               WHERE medicineId=? AND quantity>0 AND (expiryDate IS NULL OR expiryDate>=?)
                               ORDER BY CASE WHEN expiryDate IS NULL THEN 1 ELSE 0 END, expiryDate, id""",
                            arrayOf(medicineId, LocalDate.now().toString())
                        ).use { cursor ->
                            while (cursor.moveToNext()) eligibleBatches += cursor.getLong(0) to cursor.getInt(1)
                        }
                        var remaining = qty
                        eligibleBatches.forEach { (batchId, batchQuantity) ->
                            if (remaining > 0) {
                                val taken = minOf(remaining, batchQuantity)
                                val batchBalance = batchQuantity - taken
                                db.execSQL("UPDATE local_medicine_batches SET quantity=? WHERE id=?", arrayOf(batchBalance, batchId))
                                db.execSQL(
                                    """INSERT INTO local_medicine_batch_movements(sourceRecordId,batchId,medicineId,movementType,quantityDelta,balanceAfter,reference,occurredAt)
                                       VALUES(?,?,?,'dispense',?,?,?,?)""",
                                    arrayOf(sourceRecordId, batchId, medicineId, -taken, batchBalance, record.title, now)
                                )
                                remaining -= taken
                            }
                        }
                        require(remaining == 0) { "المخزون الصالح للصرف غير كافٍ." }
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
                "invoices" -> {
                    val invoiceNumber = f.field("رقم الفاتورة").ifBlank { record.title }
                    val invoiceId = db.long("SELECT id FROM invoices WHERE invoiceNumber=? LIMIT 1", invoiceNumber)
                    if (invoiceId != null) {
                        val total = db.long("SELECT amountMinor FROM invoices WHERE id=?", invoiceId) ?: 0L
                        val paid = db.long("SELECT COALESCE(SUM(amountMinor),0) FROM payments WHERE invoiceId=?", invoiceId) ?: 0L
                        val entered = f.field("الحالة")
                        val status = when {
                            entered.contains("ملغ") || entered.equals("void", true) -> "void"
                            paid <= 0L -> "unpaid"
                            paid < total -> "partial"
                            else -> "paid"
                        }
                        db.execSQL("UPDATE invoices SET status=? WHERE id=?", arrayOf(status, invoiceId))
                    }
                }
                "payments" -> {
                    val invoiceNumber = f.field("رقم الفاتورة").ifBlank { record.title }
                    val invoiceId = db.long("SELECT id FROM invoices WHERE invoiceNumber=? LIMIT 1", invoiceNumber)
                    if (invoiceId != null) {
                        val total = db.long("SELECT amountMinor FROM invoices WHERE id=?", invoiceId) ?: 0L
                        val paid = db.long("SELECT COALESCE(SUM(amountMinor),0) FROM payments WHERE invoiceId=?", invoiceId) ?: 0L
                        val currentStatus = db.string("SELECT status FROM invoices WHERE id=?", invoiceId).orEmpty()
                        if (!currentStatus.equals("void", true)) {
                            val status = when {
                                paid <= 0L -> "unpaid"
                                paid < total -> "partial"
                                else -> "paid"
                            }
                            db.execSQL("UPDATE invoices SET status=? WHERE id=?", arrayOf(status, invoiceId))
                        }
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
            val previousEvent = db.long(
                "SELECT id FROM local_audit_log WHERE entityType='record' AND entityId=? LIMIT 1",
                sourceRecordId.toString()
            )
            val action = if (previousEvent == null) "record.created" else "record.updated"
            val auditDetails = """{"category":"${jsonEscape(record.category)}","title":"${jsonEscape(record.title)}"}"""
            appendAudit(db, action, "record", sourceRecordId.toString(), auditDetails, now)
            queueFinanceEvent(db, record, sourceRecordId, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Prevent generic-record deletion from silently breaking operational stock/finance state. */
    fun validateDeletion(record: HospitalRecord) {
        val db = database.openHelper.readableDatabase
        when (record.category) {
            "pharmacy" -> require(db.long(
                """SELECT id FROM local_medicine_movements WHERE sourceRecordId=?
                   UNION ALL SELECT id FROM local_medicines WHERE sourceRecordId=? LIMIT 1""",
                record.id, record.id
            ) == null) {
                "سجل توريد الدواء مرتبط بالمخزون؛ لا تحذفه. أنشئ توريداً جديداً أو حركة تصحيح موثقة."
            }
            "prescriptions" -> require(db.long(
                "SELECT id FROM local_prescriptions WHERE sourceRecordId=? LIMIT 1", record.id
            ) == null) {
                "الوصفة مرتبطة بسجل الصيدلية؛ لا تحذفها حتى لا ينفصل السجل التشغيلي عن سجل الوصفة."
            }
            "payments" -> require(db.long(
                "SELECT id FROM payments WHERE createdAt=(SELECT createdAt FROM hospital_records WHERE id=?) LIMIT 1",
                record.id
            ) == null) {
                "لا يمكن حذف دفعة مالية مسجلة؛ حافظ على أثرها المحاسبي واستخدم إجراء عكس موثق."
            }
            "users" -> require(db.long(
                "SELECT id FROM local_users WHERE sourceRecordId=? LIMIT 1", record.id
            ) == null) { "المستخدم مرتبط بسجل تشغيلي محلي؛ عطّله بدلاً من حذفه." }
            "encounters" -> require(db.long(
                "SELECT id FROM local_encounters WHERE sourceRecordId=? LIMIT 1", record.id
            ) == null) { "الزيارة مرتبطة بالسجل التشغيلي المحلي؛ أغلقها بدلاً من حذفها." }
            "beds" -> require(db.long(
                "SELECT id FROM local_beds WHERE sourceRecordId=? LIMIT 1", record.id
            ) == null) { "السرير مسجل في إدارة الأسرة؛ لا تحذف سجل تعريفه مباشرة." }
            "appointments" -> require(db.long(
                "SELECT id FROM local_appointments WHERE sourceRecordId=? LIMIT 1", record.id
            ) == null) { "الموعد مرتبط بسجل المواعيد المحلي؛ غيّر حالته بدلاً من حذفه." }
            "lab" -> require(db.long(
                "SELECT id FROM local_lab_order_events WHERE sourceRecordId=? LIMIT 1", record.id
            ) == null) { "طلب المختبر له سجل حالات؛ حافظ على التسلسل التاريخي ولا تحذفه." }
            "admissions" -> require(db.long(
                "SELECT id FROM local_admissions WHERE sourceRecordId=? LIMIT 1", record.id
            ) == null) { "التنويم له سجل تشغيلي؛ حافظ على سجل الدخول والخروج ولا تحذفه." }
            "invoices" -> {
                val invoiceNumber = fields(record).field("رقم الفاتورة").ifBlank { record.title }
                require(db.long("SELECT id FROM payments WHERE invoiceNumber=? LIMIT 1", invoiceNumber) == null) {
                    "لا يمكن حذف فاتورة لها مدفوعات مسجلة."
                }
                require(db.long(
                    "SELECT id FROM local_finance_outbox WHERE aggregateType='invoice' AND aggregateId=? LIMIT 1",
                    record.id.toString()
                ) == null) { "الفاتورة لها حدث مالي محفوظ؛ لا تحذفها قبل معالجة الحدث وفق سياسة مالية موثقة." }
            }
        }
    }

    fun auditDeletion(record: HospitalRecord) {
        val db = database.openHelper.writableDatabase
        val now = LocalDateTime.now().format(timestampFormatter)
        val details = """{"category":"${jsonEscape(record.category)}","title":"${jsonEscape(record.title)}"}"""
        db.execSQL(
            "INSERT INTO local_audit_log(actor,action,entityType,entityId,detailsJson,occurredAt) VALUES(?,?,?,?,?,?)",
            arrayOf("local-user", "record.deleted", "record", record.id.toString(), details, now)
        )
    }

    fun rows(category: String): List<Pair<String, String>> {
        val db = database.openHelper.readableDatabase
        val sql = when (category) {
            "audit" -> """SELECT action || ' • ' || entityType || ' #' || entityId,
                occurredAt || ' • ' || detailsJson FROM local_audit_log ORDER BY id DESC LIMIT 500"""
            "finance_outbox" -> """SELECT eventType || ' • ' || aggregateType || ' #' || aggregateId,
                status || ' • محاولات: ' || attempts || ' • ' || updatedAt ||
                CASE WHEN lastError IS NULL OR lastError='' THEN '' ELSE ' • ' || lastError END
                FROM local_finance_outbox ORDER BY id DESC LIMIT 500"""
            "pharmacy" -> """SELECT name || ' (' || sku || ')',
                'إجمالي المخزون: ' || quantity ||
                ' • الصالح للصرف: ' || (SELECT COALESCE(SUM(b.quantity),0) FROM local_medicine_batches b
                    WHERE b.medicineId=local_medicines.id AND b.quantity>0
                    AND (b.expiryDate IS NULL OR b.expiryDate>=date('now','localtime'))) ||
                ' • حد الطلب: ' || reorderLevel || ' • ' || unit ||
                ' • تشغيلات: ' || (SELECT COUNT(*) FROM local_medicine_batches b WHERE b.medicineId=local_medicines.id AND b.quantity>0)
                FROM local_medicines ORDER BY name COLLATE NOCASE"""
            "beds" -> """SELECT b.ward || ' • سرير ' || b.bedNo,
                CASE WHEN b.active=0 THEN 'غير متاح'
                     WHEN EXISTS(SELECT 1 FROM local_admissions a WHERE a.ward=b.ward AND a.bedNo=b.bedNo AND a.status='admitted')
                     THEN 'مشغول' ELSE 'متاح' END || CASE WHEN b.notes='' THEN '' ELSE ' • ' || b.notes END
                FROM local_beds b ORDER BY b.ward, b.bedNo"""
            "admissions" -> """SELECT patientName || ' • ' || ward || ' / ' || bedNo,
                status || ' • دخول: ' || admittedAt || CASE WHEN dischargedAt IS NULL THEN '' ELSE ' • خروج: ' || dischargedAt END
                FROM local_admissions ORDER BY id DESC"""
            "appointments" -> """SELECT patientName || ' • ' || doctorName, status || ' • ' || startsAt || ' — ' || endsAt
                FROM local_appointments ORDER BY startsAt DESC"""
            "lab" -> """SELECT h.title, e.status || ' • ' || e.occurredAt ||
                CASE WHEN e.result='' THEN '' ELSE ' • ' || e.result END
                FROM local_lab_order_events e INNER JOIN hospital_records h ON h.id=e.sourceRecordId
                WHERE e.id IN (SELECT MAX(id) FROM local_lab_order_events GROUP BY sourceRecordId)
                ORDER BY e.id DESC"""
            "users" -> """SELECT username, role || CASE WHEN active=1 THEN ' • نشط' ELSE ' • غير نشط' END
                FROM local_users ORDER BY username COLLATE NOCASE"""
            "encounters" -> """SELECT patientName || CASE WHEN doctorName='' THEN '' ELSE ' • ' || doctorName END,
                status || ' • فتح: ' || openedAt || CASE WHEN closedAt IS NULL THEN '' ELSE ' • إغلاق: ' || closedAt END
                FROM local_encounters ORDER BY id DESC"""
            "invoices" -> """SELECT invoiceNumber || ' • ' || patientName,
                status || ' • الإجمالي: ' || amountMinor || ' • المدفوع: ' ||
                (SELECT COALESCE(SUM(p.amountMinor),0) FROM payments p WHERE p.invoiceId=invoices.id) ||
                ' • المتبقي: ' || MAX(0, amountMinor-(SELECT COALESCE(SUM(p.amountMinor),0) FROM payments p WHERE p.invoiceId=invoices.id))
                FROM invoices ORDER BY createdAt DESC"""
            "payments" -> """SELECT invoiceNumber || ' • ' || method,
                'المبلغ بالوحدة الصغرى: ' || amountMinor || CASE WHEN receiptReference='' THEN '' ELSE ' • مرجع: ' || receiptReference END
                FROM payments ORDER BY createdAt DESC"""
            else -> return emptyList()
        }
        return db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty())
            }
        }
    }

    private fun canonicalAppointmentStatus(raw: String): String = when {
        raw.contains("حضر") || raw.equals("checked_in", true) -> if (raw.contains("لم يحضر")) "no_show" else "checked_in"
        raw.contains("مكتمل") || raw.equals("completed", true) -> "completed"
        raw.contains("ملغ") || raw.equals("cancelled", true) -> "cancelled"
        raw.contains("عدم الحضور") || raw.equals("no_show", true) -> "no_show"
        else -> "scheduled"
    }

    private fun canonicalUserRole(raw: String): String? = when {
        raw.contains("مدير") || raw.equals("admin", true) -> "admin"
        raw.contains("طبيب") || raw.equals("doctor", true) -> "doctor"
        raw.contains("ممرض") || raw.equals("nurse", true) -> "nurse"
        raw.contains("استقبال") || raw.equals("reception", true) -> "reception"
        raw.contains("مختبر") || raw.equals("lab", true) -> "lab"
        raw.contains("صيدلي") || raw.equals("pharmacist", true) -> "pharmacist"
        raw.contains("محاسب") || raw.equals("accountant", true) -> "accountant"
        else -> null
    }

    private fun canonicalLabStatus(raw: String): String = when {
        raw.contains("جمع") || raw.contains("collected", true) -> "collected"
        raw.contains("تنفيذ") || raw.contains("processing", true) -> "processing"
        raw.contains("مكتمل") || raw.contains("نتيج") || raw.contains("resulted", true) -> "resulted"
        raw.contains("لغ") || raw.contains("cancel", true) -> "cancelled"
        else -> "ordered"
    }
}
