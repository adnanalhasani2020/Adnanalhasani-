package com.adnanalhasani.commercial

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.time.LocalDate

/**
 * Owns interactive state for the reports center. The screen delegates to these
 * state holders while keeping report calculations and source orchestration
 * independent from the lifetime of individual UI controls.
 */
internal class ReportsCenterUiState(today: LocalDate) {
    val selectedPeriod: MutableState<String> = mutableStateOf("الشهر الحالي")
    val startText: MutableState<String> = mutableStateOf(today.withDayOfMonth(1).toString())
    val endText: MutableState<String> = mutableStateOf(today.toString())
    val search: MutableState<String> = mutableStateOf("")
    val rowFilter: MutableState<String> = mutableStateOf("")
    val currencyFilter: MutableState<String> = mutableStateOf("")
    val statusFilter: MutableState<String> = mutableStateOf("")
    val accountFilter: MutableState<String> = mutableStateOf("")
    val customerFilter: MutableState<String> = mutableStateOf("")
    val selectedCategory: MutableState<String> = mutableStateOf("الكل")
    val selectedReport: MutableState<String> = mutableStateOf("ملخص المبيعات حسب الفترة")
    val exportRows: MutableState<List<List<String>>> = mutableStateOf(emptyList())
    val exportTitle: MutableState<String> = mutableStateOf("تقرير متجري")
    val error: MutableState<String> = mutableStateOf("")
    val supplierFilter: MutableState<String> = mutableStateOf("")
    val cloudReload: MutableIntState = mutableIntStateOf(0)
}
