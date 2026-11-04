package com.adnanalhasani.commercial

import org.json.JSONObject

/** Owns product-detail updates and source-ruling review RPCs. */
internal class CatalogProductMaintenanceWorkflow(
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val request: (action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)?) -> Unit,
    private val onFeedback: (String) -> Unit,
    private val onProductUpdated: (CatalogProduct, String) -> Unit,
    private val onRulingReviewed: (String) -> Unit
) {
    fun reviewRuling(rulingId: String) {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة UUID أولًا.")
            return
        }
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_catalog_review_ruling",
                JSONObject().put("p_organization_id", orgId).put("p_ruling_id", rulingId),
                active
            )
        }, { result -> onRulingReviewed(result) }, null)
    }

    fun updateProductDetails(item: CatalogProduct, payload: JSONObject) {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة UUID أولًا.")
            return
        }
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_catalog_update_product_details",
                JSONObject()
                    .put("p_organization_id", orgId)
                    .put("p_product_id", item.id)
                    .put("p_product", payload),
                active
            )
        }, { result -> onProductUpdated(item, result) }, null)
    }
}
