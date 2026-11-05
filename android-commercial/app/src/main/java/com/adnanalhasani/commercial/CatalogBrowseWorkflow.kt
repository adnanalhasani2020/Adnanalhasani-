package com.adnanalhasani.commercial

import org.json.JSONObject

/** Owns paged discovery requests; scrolling and list lifecycle remain in CatalogScreen. */
internal class CatalogBrowseWorkflow(
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val activityCode: () -> String,
    private val sector: () -> String,
    private val businessLevel: () -> String,
    private val country: () -> String,
    private val region: () -> String,
    private val city: () -> String,
    private val locale: () -> String,
    private val query: () -> String,
    private val signedInUserId: () -> String,
    private val currentItems: () -> List<CatalogProduct>,
    private val currentOffset: () -> Int,
    private val hasMore: () -> Boolean,
    private val pageLoadFailed: () -> Boolean,
    private val busy: () -> Boolean,
    private val setItems: (List<CatalogProduct>) -> Unit,
    private val setOffset: (Int) -> Unit,
    private val setHasMore: (Boolean) -> Unit,
    private val setPageLoadFailed: (Boolean) -> Unit,
    private val request: (action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)?) -> Unit,
    private val onFeedback: (String) -> Unit
) {
    fun loadCatalog(reset: Boolean = true) {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة UUID أولًا.")
            return
        }
        if (busy() || (!reset && !hasMore())) return
        setPageLoadFailed(false)
        val requestedOffset = if (reset) 0 else currentOffset()
        if (reset) {
            setOffset(0)
            setHasMore(true)
        }
        request({
            val payload = JSONObject()
                .put("p_organization_id", orgId)
                .put("p_activity_node_code", activityCode().trim().uppercase().ifBlank { JSONObject.NULL })
                .put("p_sector_code", sector().trim().ifBlank { JSONObject.NULL })
                .put("p_business_level_code", businessLevel().trim().ifBlank { JSONObject.NULL })
                .put("p_country_code", country().trim().ifBlank { JSONObject.NULL })
                .put("p_region_code", region().trim().ifBlank { JSONObject.NULL })
                .put("p_city", city().trim().ifBlank { JSONObject.NULL })
                .put("p_locale_code", locale().trim().lowercase().ifBlank { "ar" })
                .put("p_query", query().trim().ifBlank { JSONObject.NULL })
                .put("p_limit", 100)
                .put("p_offset", requestedOffset)
            catalogHttp("/rest/v1/rpc/commercial_catalog_discover_products_page", payload, active)
        }, { raw ->
            val pageItems = parseCatalogProducts(raw, signedInUserId())
            val merged = if (reset) pageItems.distinctBy { it.id }
                else (currentItems() + pageItems).distinctBy { it.id }
            setItems(merged)
            setOffset(requestedOffset + pageItems.size)
            val more = pageItems.size == 100
            setHasMore(more)
            onFeedback(
                if (more) "تم تحميل ${merged.size} صنفًا؛ اسحب إلى الأسفل لتحميل المزيد."
                else "اكتمل تحميل ${merged.size} صنفًا مطابقًا."
            )
        }, { setPageLoadFailed(true) })
    }
}
