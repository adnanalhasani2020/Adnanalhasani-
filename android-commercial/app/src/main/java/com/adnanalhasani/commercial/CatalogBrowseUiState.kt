package com.adnanalhasani.commercial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Screen-scoped filters, selection, and pagination state for catalog browsing. */
internal class CatalogBrowseUiState(initialActivityCode: String) {
    var sector by mutableStateOf("TRADE")
    var businessLevel by mutableStateOf("shop")
    var country by mutableStateOf("YE")
    var region by mutableStateOf("")
    var city by mutableStateOf("")
    var locale by mutableStateOf("ar")
    var query by mutableStateOf("")
    var items by mutableStateOf<List<CatalogProduct>>(emptyList())
    var catalogOffset by mutableIntStateOf(0)
    var catalogHasMore by mutableStateOf(true)
    var catalogPageLoadFailed by mutableStateOf(false)
    var selectedCatalogIds by mutableStateOf<Set<String>>(emptySet())
    var activityNodes by mutableStateOf<List<CatalogActivityNode>>(emptyList())
    var selectedActivityCode by mutableStateOf(initialActivityCode.uppercase())
    var sectorMenuExpanded by mutableStateOf(false)
    var businessLevelMenuExpanded by mutableStateOf(false)
    var activityMenuExpanded by mutableStateOf(false)
}
