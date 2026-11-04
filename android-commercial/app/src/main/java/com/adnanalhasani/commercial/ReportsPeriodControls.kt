package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Date-range controls only; report date validation and filtering stay with the screen. */
@Composable
internal fun ReportsPeriodControls(
    selectedPeriod: String,
    startText: String,
    endText: String,
    dateError: Boolean,
    onPeriodSelected: (String) -> Unit,
    onRangeChanged: (String, String) -> Unit,
    onClearError: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الفترة", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = ReportInk)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("اليوم", "أمس", "الأسبوع الحالي", "الأسبوع السابق", "الشهر الحالي", "الشهر السابق", "الربع الحالي", "الربع السابق", "السنة الحالية", "السنة السابقة").forEach { label ->
                FilterChip(selected = selectedPeriod == label, onClick = {
                    onPeriodSelected(label)
                    val now = LocalDate.now()
                    val range = when (label) {
                        "اليوم" -> now to now
                        "أمس" -> now.minusDays(1) to now.minusDays(1)
                        "الأسبوع الحالي" -> now.with(java.time.DayOfWeek.MONDAY) to now
                        "الأسبوع السابق" -> now.with(java.time.DayOfWeek.MONDAY).minusWeeks(1) to now.with(java.time.DayOfWeek.MONDAY).minusDays(1)
                        "الشهر الحالي" -> now.withDayOfMonth(1) to now
                        "الشهر السابق" -> now.minusMonths(1).withDayOfMonth(1) to now.withDayOfMonth(1).minusDays(1)
                        "الربع الحالي" -> now.withMonth(((now.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1) to now
                        "الربع السابق" -> {
                            val currentQuarterStart = now.withMonth(((now.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1)
                            val previousQuarterEnd = currentQuarterStart.minusDays(1)
                            previousQuarterEnd.minusMonths(2).withDayOfMonth(1) to previousQuarterEnd
                        }
                        "السنة السابقة" -> now.minusYears(1).withDayOfYear(1) to now.withDayOfYear(1).minusDays(1)
                        else -> now.withDayOfYear(1) to now
                    }
                    onRangeChanged(range.first.toString(), range.second.toString())
                    onClearError()
                }, label = { Text(label, fontSize = 11.sp) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(startText, { onRangeChanged(it, endText); onPeriodSelected("مخصص") }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("من YYYY-MM-DD") })
            OutlinedTextField(endText, { onRangeChanged(startText, it); onPeriodSelected("مخصص") }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("إلى YYYY-MM-DD") })
        }
        if (dateError) Text("تحقق من التاريخين؛ يجب أن تكون البداية قبل النهاية أو مساوية لها.", color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp)
    }
}
