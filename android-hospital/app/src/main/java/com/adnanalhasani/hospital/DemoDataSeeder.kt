package com.adnanalhasani.hospital

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adds clearly labelled fictional records only on a genuinely empty patient database.
 * This is demo data for development/testing, never real patient information.
 */
object DemoDataSeeder {
    private val mutex = Mutex()

    suspend fun seedIfEmpty(dao: HospitalDao) = mutex.withLock {
        if (dao.patients().first().isNotEmpty()) return
        if (dao.records("patients").first().isNotEmpty()) return

        val now = System.currentTimeMillis()
        val patients = listOf(
            Triple("DEMO-1001", "مريض تجريبي - أحمد سالم", "34"),
            Triple("DEMO-1002", "مريضة تجريبية - سارة علي", "28"),
            Triple("DEMO-1003", "مريض تجريبي - محمد ياسين", "52"),
            Triple("DEMO-1004", "مريضة تجريبية - مريم حسن", "41"),
            Triple("DEMO-1005", "مريض تجريبي - خالد ناصر", "19")
        )
        patients.forEachIndexed { index, (fileNumber, name, age) ->
            dao.saveRecord(
                HospitalRecord(
                    category = "patients",
                    title = fileNumber,
                    details = "اسم المريض: $name\nرقم الهاتف: 0000000000\nالعمر: $age\nملاحظات: بيانات تجريبية وهمية للاختبار فقط",
                    createdAt = now - (index * 60_000L)
                )
            )
        }

        val doctors = listOf(
            "DEMO-DR-01" to ("طبيب تجريبي - د. سامي إبراهيم" to "طب الأسرة"),
            "DEMO-DR-02" to ("طبيبة تجريبية - د. ليلى عمر" to "الباطنية")
        )
        doctors.forEachIndexed { index, (employeeNumber, profile) ->
            val (name, specialty) = profile
            dao.saveRecord(
                HospitalRecord(
                    category = "doctors",
                    title = employeeNumber,
                    details = "اسم الطبيب: $name\nالتخصص: $specialty\nرقم الهاتف: 0000000000",
                    createdAt = now - (index * 60_000L)
                )
            )
        }

        val appointments = listOf(
            Triple("DEMO-1001", "DEMO-DR-01", 0),
            Triple("DEMO-1002", "DEMO-DR-02", 1),
            Triple("DEMO-1003", "DEMO-DR-01", 2),
            Triple("DEMO-1004", "DEMO-DR-02", 3)
        )
        val patientNames = patients.associate { it.first to it.second }
        val doctorNames: Map<String, String> = doctors.associate { doctor -> doctor.first to doctor.second.first }
        val reasons = listOf("مراجعة عامة (تجريبية)", "متابعة دورية (تجريبية)", "استشارة أولية (تجريبية)", "متابعة أعراض (تجريبية)")
        appointments.forEachIndexed { index, (fileNumber, employeeNumber, dayOffset) ->
            dao.saveRecord(
                HospitalRecord(
                    category = "appointments",
                    title = "DEMO-APT-${index + 1}",
                    details = "اسم المريض: ${patientNames.getValue(fileNumber)}\nاسم الطبيب: ${doctorNames.getValue(employeeNumber)}\nالتاريخ والوقت: ${dateTime(dayOffset)}\nسبب الزيارة: ${reasons[index]}",
                    createdAt = now + index
                )
            )
        }

        val visits = listOf(
            Triple("DEMO-1003", "DEMO-DR-01", -1),
            Triple("DEMO-1005", "DEMO-DR-02", -2)
        )
        visits.forEachIndexed { index, (fileNumber, employeeNumber, dayOffset) ->
            val patientName = patientNames.getValue(fileNumber)
            val doctorName = doctorNames.getValue(employeeNumber)
            dao.saveRecord(
                HospitalRecord(
                    category = "encounters",
                    title = patientName,
                    details = "اسم المريض: $patientName\nاسم الطبيب: $doctorName\nتاريخ الزيارة: ${dateTime(dayOffset)}\nالشكوى الرئيسية: شكوى تجريبية\nالتشخيص: لا يوجد تشخيص حقيقي - بيانات تدريبية\nملاحظات: سجل وهمي للتأكد من عمل شاشة الطبيب",
                    createdAt = now - ((index + 1) * 60_000L)
                )
            )
        }
    }

    private fun dateTime(dayOffset: Int): String {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, if (dayOffset <= 0) 10 else 11)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(calendar.time)
    }
}
