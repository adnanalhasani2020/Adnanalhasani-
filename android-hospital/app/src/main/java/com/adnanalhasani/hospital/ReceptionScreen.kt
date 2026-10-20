package com.adnanalhasani.hospital

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceptionScreen(repository: HospitalRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doctors by repository.doctors().collectAsState(initial = emptyList())
    var fileNumber by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var identityNumber by remember { mutableStateOf("") }
    var dateOfBirth by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var bloodGroup by remember { mutableStateOf("") }
    var allergies by remember { mutableStateOf("") }
    var medicalHistory by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("") }
    var selectedDoctorId by remember { mutableStateOf<Long?>(null) }
    var doctorName by remember { mutableStateOf("") }
    var complaint by remember { mutableStateOf("") }
    var invoiceNumber by remember { mutableStateOf("") }
    var totalAmount by remember { mutableStateOf("") }
    var paidAmount by remember { mutableStateOf("0") }
    var paymentMethod by remember { mutableStateOf("نقداً") }
    var receiptReference by remember { mutableStateOf("") }
    var doctorExpanded by remember { mutableStateOf(false) }
    var genderExpanded by remember { mutableStateOf(false) }
    var bloodExpanded by remember { mutableStateOf(false) }
    var paymentExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val matchingDoctors = doctors.filter { doctor ->
        department.isBlank() || doctor.specialty.contains(department.trim(), ignoreCase = true) ||
            department.contains(doctor.specialty, ignoreCase = true)
    }
    val doctorOptions = matchingDoctors.ifEmpty { doctors }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    fun chooseDate(current: String, onSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        runCatching { dateFormat.parse(current) }.getOrNull()?.let { calendar.time = it }
        DatePickerDialog(
            context,
            { _, year, month, day -> onSelected(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)) },
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("الاستقبال والتحصيل", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
            Text("تسجيل ملف المريض والزيارة والفاتورة والدفع في قاعدة البيانات، ثم إحالة الزيارة للطبيب المحدد.", fontSize = 12.sp, color = Color(0xFF788895))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("بيانات المريض", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                    OutlinedTextField(fileNumber, { fileNumber = it; message = null }, label = { Text("رقم الملف *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(fullName, { fullName = it; message = null }, label = { Text("اسم المريض الكامل *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(identityNumber, { identityNumber = it }, label = { Text("رقم الهوية أو الوثيقة") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(dateOfBirth, {}, readOnly = true, label = { Text("تاريخ الميلاد") }, modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton(onClick = { chooseDate(dateOfBirth) { dateOfBirth = it } }) { Icon(Icons.Default.CalendarMonth, "اختيار تاريخ الميلاد") } })
                    OutlinedTextField(age, { age = it }, label = { Text("العمر") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    ExposedDropdownMenuBox(expanded = genderExpanded, onExpandedChange = { genderExpanded = !genderExpanded }) {
                        OutlinedTextField(gender, {}, readOnly = true, label = { Text("الجنس") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = genderExpanded, onDismissRequest = { genderExpanded = false }) {
                            listOf("ذكر", "أنثى", "غير محدد").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { gender = option; genderExpanded = false }) }
                        }
                    }
                    OutlinedTextField(phone, { phone = it }, label = { Text("رقم الهاتف") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(address, { address = it }, label = { Text("العنوان") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    ExposedDropdownMenuBox(expanded = bloodExpanded, onExpandedChange = { bloodExpanded = !bloodExpanded }) {
                        OutlinedTextField(bloodGroup, {}, readOnly = true, label = { Text("فصيلة الدم") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = bloodExpanded, onDismissRequest = { bloodExpanded = false }) {
                            listOf("غير معروفة", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { bloodGroup = option; bloodExpanded = false }) }
                        }
                    }
                    OutlinedTextField(allergies, { allergies = it }, label = { Text("الحساسية المعروفة") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(medicalHistory, { medicalHistory = it }, label = { Text("التاريخ الطبي الأساسي والأدوية الحالية") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("إحالة المريض للعيادة", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                    OutlinedTextField(
                        department,
                        { value ->
                            department = value
                            message = null
                            val matches = doctors.filter { doctor ->
                                value.isNotBlank() && (
                                    doctor.specialty.contains(value.trim(), ignoreCase = true) ||
                                    value.trim().contains(doctor.specialty, ignoreCase = true)
                                )
                            }
                            if (matches.size == 1) {
                                selectedDoctorId = matches.first().id
                                doctorName = matches.first().fullName
                            } else {
                                selectedDoctorId = null
                                doctorName = ""
                            }
                        },
                        label = { Text("القسم / العيادة *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenuBox(expanded = doctorExpanded, onExpandedChange = { doctorExpanded = !doctorExpanded }) {
                        OutlinedTextField(doctorName, {}, readOnly = true, label = { Text("الطبيب المختص *") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = doctorExpanded, onDismissRequest = { doctorExpanded = false }) {
                            doctorOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("${option.fullName} • ${option.specialty.ifBlank { "تخصص غير محدد" }}") },
                                    onClick = { selectedDoctorId = option.id; doctorName = option.fullName; doctorExpanded = false }
                                )
                            }
                        }
                    }
                    if (doctors.isEmpty()) Text("لا يوجد أطباء مسجلون. أضف الطبيب وتخصصه من شاشة الأطباء أولاً.", color = Color(0xFFC97979), fontSize = 12.sp)
                    else if (department.isNotBlank() && matchingDoctors.isEmpty()) Text("لا يوجد طبيب بتخصص مطابق تماماً؛ تظهر القائمة الكاملة للاختيار اليدوي.", color = Color(0xFF9A6B28), fontSize = 12.sp)
                    OutlinedTextField(complaint, { complaint = it }, label = { Text("سبب الزيارة / الشكوى *") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("الفاتورة والتحصيل", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                    OutlinedTextField(invoiceNumber, { invoiceNumber = it; message = null }, label = { Text("رقم الفاتورة *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(totalAmount, { totalAmount = it; message = null }, label = { Text("إجمالي الرسوم *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(paidAmount, { paidAmount = it; message = null }, label = { Text("المبلغ المدفوع") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    ExposedDropdownMenuBox(expanded = paymentExpanded, onExpandedChange = { paymentExpanded = !paymentExpanded }) {
                        OutlinedTextField(paymentMethod, {}, readOnly = true, label = { Text("طريقة الدفع") }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = paymentExpanded, onDismissRequest = { paymentExpanded = false }) {
                            listOf("نقداً", "بطاقة", "تحويل بنكي", "تأمين").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { paymentMethod = option; paymentExpanded = false }) }
                        }
                    }
                    OutlinedTextField(receiptReference, { receiptReference = it }, label = { Text("مرجع الإيصال") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("العملة الافتراضية: الريال اليمني (YER). لا تستخدم بيانات مرضى حقيقية أثناء الاختبار.", color = Color(0xFF788895), fontSize = 11.sp)
                }
            }
        }
        item {
            Button(
                onClick = {
                    val total = totalAmount.trim().toBigDecimalOrNull()
                    val paid = paidAmount.trim().ifBlank { "0" }.toBigDecimalOrNull()
                    when {
                        fileNumber.isBlank() || fullName.isBlank() || department.isBlank() || complaint.isBlank() || invoiceNumber.isBlank() || total == null || paid == null -> message = "أكمل الحقول المطلوبة وأدخل مبالغ رقمية صحيحة."
                        selectedDoctorId == null || doctorName.isBlank() -> message = "اختر الطبيب المختص حتى تظهر الزيارة في شاشة الطبيب."
                        total < java.math.BigDecimal.ZERO || paid < java.math.BigDecimal.ZERO || paid > total -> message = "تحقق من المبالغ: المدفوع يجب ألا يتجاوز إجمالي الرسوم."
                        else -> {
                            saving = true
                            message = null
                            val now = System.currentTimeMillis()
                            val patientNotes = listOf(
                                "رقم الهوية: ${identityNumber.ifBlank { "غير مدخل" }}",
                                "تاريخ الميلاد: ${dateOfBirth.ifBlank { "غير محدد" }}",
                                "الجنس: ${gender.ifBlank { "غير محدد" }}",
                                "العنوان: ${address.ifBlank { "غير مدخل" }}",
                                "فصيلة الدم: ${bloodGroup.ifBlank { "غير معروفة" }}",
                                "الحساسية: ${allergies.ifBlank { "غير مدخلة" }}",
                                "التاريخ الطبي الأساسي: ${medicalHistory.ifBlank { "غير مدخل" }}"
                            ).joinToString(" | ")
                            val patientRecord = HospitalRecord(category = "patients", title = fileNumber.trim(), details = "اسم المريض: ${fullName.trim()}\nرقم الهاتف: ${phone.trim()}\nالعمر: ${age.trim()}\nملاحظات: $patientNotes", createdAt = now)
                            val visitRecord = HospitalRecord(category = "encounters", title = fullName.trim(), details = listOf(
                                "اسم المريض: ${fullName.trim()}", "رقم الملف: ${fileNumber.trim()}",
                                "اسم الطبيب: ${doctorName.trim()}", "القسم / العيادة: ${department.trim()}",
                                "تاريخ الزيارة: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))}",
                                "الشكوى الرئيسية: ${complaint.trim()}", "التشخيص: ", "ملاحظات: حالة الإحالة: بانتظار الطبيب"
                            ).joinToString("\n"), createdAt = now)
                            val invoiceStatus = if (paid.compareTo(total) >= 0) "paid" else "open"
                            val invoiceRecord = HospitalRecord(category = "invoices", title = invoiceNumber.trim(), details = "اسم المريض: ${fullName.trim()}\nالمبلغ: ${total.stripTrailingZeros().toPlainString()}\nالعملة: YER\nالحالة: $invoiceStatus", createdAt = now)
                            val paymentRecord = if (paid > java.math.BigDecimal.ZERO) HospitalRecord(
                                category = "payments", title = (receiptReference.ifBlank { "REC-${now}" }).trim(),
                                details = "رقم الفاتورة: ${invoiceNumber.trim()}\nالمبلغ المدفوع: ${paid.stripTrailingZeros().toPlainString()}\nطريقة الدفع: ${paymentMethod.trim()}\nمرجع الإيصال: ${receiptReference.trim()}",
                                createdAt = now
                            ) else null
                            scope.launch {
                                try {
                                    repository.registerReception(patientRecord, visitRecord, invoiceRecord, paymentRecord, selectedDoctorId)
                                    message = "تم حفظ ملف المريض والزيارة والفاتورة ${if (paymentRecord != null) "والدفعة" else ""}. ظهرت الزيارة في قائمة الطبيب المختار."
                                    fileNumber = ""; fullName = ""; identityNumber = ""; dateOfBirth = ""; age = ""; gender = ""; phone = ""; address = ""; bloodGroup = ""; allergies = ""; medicalHistory = ""
                                    department = ""; selectedDoctorId = null; doctorName = ""; complaint = ""; invoiceNumber = ""; totalAmount = ""; paidAmount = "0"; receiptReference = ""
                                } catch (e: Exception) {
                                    message = "تعذر حفظ بيانات الاستقبال كاملة؛ لم يتم عرض نجاح. راجع رقم الملف ورقم الفاتورة والبيانات ثم حاول مجدداً."
                                } finally { saving = false }
                            }
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (saving) "جارٍ الحفظ..." else "حفظ وإحالة للطبيب")
            }
            message?.let { Text(it, color = if (it.startsWith("تم")) Color(0xFF548F88) else Color(0xFFC97979), fontSize = 12.sp) }
        }
    }
}
