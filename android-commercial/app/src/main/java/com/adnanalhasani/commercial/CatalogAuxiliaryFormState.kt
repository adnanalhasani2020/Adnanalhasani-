package com.adnanalhasani.commercial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screen-scoped state for category and unit directory forms. */
internal class CatalogDirectoryFormState {
    var categoryCode by mutableStateOf("")
    var parentCode by mutableStateOf("")
    var categoryNameAr by mutableStateOf("")
    var categoryNameEn by mutableStateOf("")
    var categoryDescriptionAr by mutableStateOf("")
    var categoryDescriptionEn by mutableStateOf("")
    var categoryTranslations by mutableStateOf("")

    var unitShortCode by mutableStateOf("")
    var unitNameAr by mutableStateOf("")
    var unitNameEn by mutableStateOf("")
    var unitDimension by mutableStateOf("count")
    var unitFactor by mutableStateOf("1")
    var unitBaseCode by mutableStateOf("EA")
    var unitDecimals by mutableStateOf("0")
    var unitTranslations by mutableStateOf("")
}

/** Screen-scoped state for catalog transfer form inputs. */
internal class CatalogTransferFormState {
    var transferSource by mutableStateOf("")
    var transferDestination by mutableStateOf("")
    var transferIds by mutableStateOf("")
}
