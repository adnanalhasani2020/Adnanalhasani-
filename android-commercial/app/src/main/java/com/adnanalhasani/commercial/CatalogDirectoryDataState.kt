package com.adnanalhasani.commercial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared directory data consumed by catalog workflows and invoice matching. */
internal class CatalogDirectoryDataState {
    var localUnitCodes by mutableStateOf<Set<String>>(emptySet())
    var categories by mutableStateOf<List<CatalogCategory>>(emptyList())
    var directoryUnits by mutableStateOf<List<DirectoryUnit>>(emptyList())
}
