package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

/** Links selected global products without pricing and optionally assigns them to an activity. */
internal class CatalogProductLinkWorkflow(
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val selectedCatalogIds: () -> Set<String>,
    private val selectedActivityCode: () -> String,
    private val request: (action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)?) -> Unit,
    private val onSelectionCleared: () -> Unit,
    private val onFeedback: (String) -> Unit
) {
    fun linkSelectedItems() {
        val active = accessToken()
        val orgId = organizationId()
        if (active == null || orgId == null) {
            onFeedback("أدخل معرّف المؤسسة الصحيح وسجّل الدخول.")
            return
        }
        val selectedIds = selectedCatalogIds()
        if (selectedIds.size > 100) {
            onFeedback("الحد الأقصى للعملية الواحدة 100 صنف.")
            return
        }
        val ids = selectedIds.toList()
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_catalog_link_unpriced_items",
                JSONObject().put("p_organization_id", orgId).put("p_global_item_ids", JSONArray(ids)),
                active
            )
        }, { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            val added = result?.optInt("newly_linked_count", -1) ?: -1
            val existing = result?.optInt("already_linked_count", -1) ?: -1
            if (selectedActivityCode().isBlank()) {
                onSelectionCleared()
                onFeedback(
                    if (added >= 0 && existing >= 0) {
                        "تم ربط $added صنفًا جديدًا؛ $existing كان مرتبطًا مسبقًا. لم تُضبط أسعار ولم تُفعّل للبيع."
                    } else {
                        "اكتملت عملية الربط دون تسعير. الأصناف الجديدة غير متاحة للبيع."
                    }
                )
            } else {
                request({
                    catalogHttp(
                        "/rest/v1/rpc/commercial_catalog_assign_items_to_activity",
                        JSONObject()
                            .put("p_organization_id", orgId)
                            .put("p_activity_node_code", selectedActivityCode())
                            .put("p_global_item_ids", JSONArray(ids)),
                        active
                    )
                }, { mappingRaw ->
                    val mapped = runCatching { JSONObject(mappingRaw) }.getOrNull()
                    onSelectionCleared()
                    onFeedback(
                        "تم ربط $added صنفًا جديدًا ($existing موجود مسبقًا)، وإسناد ${mapped?.optInt("newly_assigned_count", 0) ?: 0} صنف للنشاط المحدد. الأصناف غير مسعّرة وغير متاحة للبيع."
                    )
                }, { failure ->
                    onFeedback(
                        "تم ربط الأصناف بدليل المؤسسة لكنها لم تُسند للنشاط: ${failure.message ?: "خطأ غير معروف"}. أعد المحاولة مع بقاء التحديد."
                    )
                })
            }
        }, null)
    }
}
