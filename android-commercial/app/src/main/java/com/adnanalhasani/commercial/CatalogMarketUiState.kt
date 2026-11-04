package com.adnanalhasani.commercial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Session-only state for catalog branch selection and nearby-market discovery.
 *
 * Network requests remain owned by catalog workflows, while foreground location
 * permission handling is isolated in the device-location controller.
 */
internal class CatalogMarketUiState {
    var marketBranches by mutableStateOf<List<CatalogBranchOption>>(emptyList())
    var marketWarehouses by mutableStateOf<List<CatalogWarehouseOption>>(emptyList())
    var selectedBranchId by mutableStateOf("")
    var selectedWarehouseId by mutableStateOf("")
    var branchLatitude by mutableStateOf("")
    var branchLongitude by mutableStateOf("")
    var branchMenuExpanded by mutableStateOf(false)
    var warehouseMenuExpanded by mutableStateOf(false)
    var nearbyMarketItems by mutableStateOf<List<NearbyCatalogItem>>(emptyList())
    var marketRadius by mutableIntStateOf(5000)

    // Device coordinates are opt-in, foreground-only and kept in memory for this screen session.
    var deviceLatitude by mutableStateOf<String?>(null)
    var deviceLongitude by mutableStateOf<String?>(null)
    var locatingDevice by mutableStateOf(false)

    var nearbyOffset by mutableIntStateOf(0)
    var nearbyHasMore by mutableStateOf(true)
    var nearbyPageLoadFailed by mutableStateOf(false)
    var nearbyLoadGeneration by mutableIntStateOf(0)
    var marketPriceHints by mutableStateOf<Map<String, String>>(emptyMap())
    /**
     * Applies the complete UI-state transition when the active market branch changes.
     * Nearby results and pagination are reset so data from the previous branch is not reused.
     */
    fun selectBranch(branch: CatalogBranchOption) {
        selectedBranchId = branch.id
        branchLatitude = branch.latitude
        branchLongitude = branch.longitude
        selectedWarehouseId = marketWarehouses.firstOrNull { it.branchId == branch.id }?.id.orEmpty()
        nearbyMarketItems = emptyList()
        nearbyOffset = 0
        nearbyHasMore = true
        nearbyLoadGeneration++
        marketRadius = 5000
        marketPriceHints = emptyMap()
        branchMenuExpanded = false
    }

}
