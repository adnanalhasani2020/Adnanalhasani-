package com.adnanalhasani.commercial

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect

/** Owns automatic pagination triggers for catalog results and nearby-market results. */
@Composable
internal fun CatalogPaginationEffects(
    section: String,
    catalogItemCount: Int,
    catalogHasMore: Boolean,
    catalogBusy: Boolean,
    catalogPageLoadFailed: Boolean,
    catalogListState: LazyListState,
    nearbyItemCount: Int,
    nearbyHasMore: Boolean,
    nearbyLoadGeneration: Int,
    nearbyBusy: Boolean,
    nearbyPageLoadFailed: Boolean,
    nearbyRadius: Int,
    nearbyListState: LazyListState,
    onLoadCatalogPage: () -> Unit,
    onLoadNearbyPage: (Boolean) -> Unit
) {
    LaunchedEffect(section, catalogItemCount, catalogHasMore, catalogBusy, catalogPageLoadFailed) {
        if (section != "browse" || catalogBusy || !catalogHasMore || catalogPageLoadFailed || catalogItemCount == 0) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = catalogListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = catalogListState.layoutInfo.totalItemsCount
            lastVisible to total
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 5 && !catalogBusy && catalogHasMore && !catalogPageLoadFailed) {
                onLoadCatalogPage()
            }
        }
    }

    LaunchedEffect(section, nearbyItemCount, nearbyHasMore, nearbyLoadGeneration, nearbyBusy, nearbyRadius) {
        if (section != "browse" || nearbyItemCount == 0 || nearbyBusy || nearbyPageLoadFailed || nearbyRadius >= 100000) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = nearbyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = nearbyListState.layoutInfo.totalItemsCount
            Triple(lastVisible, total, nearbyListState.isScrollInProgress)
        }.collect { (lastVisible, total, scrolling) ->
            if (scrolling && total > 0 && lastVisible >= total - 2 && !nearbyBusy) {
                if (nearbyHasMore) onLoadNearbyPage(false) else onLoadNearbyPage(true)
            }
        }
    }
}
