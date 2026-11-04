package com.adnanalhasani.commercial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screen-scoped authentication, request progress, and feedback state. */
internal class CatalogSessionUiState(initialOrganizationId: String) {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var token by mutableStateOf<String?>(null)
    var signedInUserId by mutableStateOf("")
    var organizationId by mutableStateOf(initialOrganizationId)

    var busy by mutableStateOf(false)
    var restoringSession by mutableStateOf(true)
    var feedback by mutableStateOf("")
}
