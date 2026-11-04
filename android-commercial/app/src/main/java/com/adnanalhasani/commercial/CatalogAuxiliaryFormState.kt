package com.adnanalhasani.commercial

import androidx.compose.runtime.mutableStateOf

/** Screen-scoped state for category and unit directory forms. */
internal class CatalogDirectoryFormState {
    val categoryCode = mutableStateOf("")
    val parentCode = mutableStateOf("")
    val categoryNameAr = mutableStateOf("")
    val categoryNameEn = mutableStateOf("")
    val categoryDescriptionAr = mutableStateOf("")
    val categoryDescriptionEn = mutableStateOf("")
    val categoryTranslations = mutableStateOf("")

    val unitShortCode = mutableStateOf("")
    val unitNameAr = mutableStateOf("")
    val unitNameEn = mutableStateOf("")
    val unitDimension = mutableStateOf("count")
    val unitFactor = mutableStateOf("1")
    val unitBaseCode = mutableStateOf("EA")
    val unitDecimals = mutableStateOf("0")
    val unitTranslations = mutableStateOf("")
}

/** Screen-scoped transfer inputs and incoming transfer results. */
internal class CatalogTransferFormState {
    val transferSource = mutableStateOf("")
    val transferDestination = mutableStateOf("")
    val transferIds = mutableStateOf("")
    val pendingTransfers = mutableStateOf<List<PendingTransfer>>(emptyList())
}
