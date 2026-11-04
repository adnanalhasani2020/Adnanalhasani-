package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

/** Coordinates catalog transfer requests and their review lifecycle. */
internal class CatalogTransferWorkflow(
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val transferSource: () -> String,
    private val transferDestination: () -> String,
    private val transferIds: () -> String,
    private val request: (action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)?) -> Unit,
    private val onPendingTransfers: (List<PendingTransfer>) -> Unit,
    private val onFeedback: (String) -> Unit
) {
    fun transferCatalog() {
        val active = accessToken() ?: return
        val source = transferSource()
        val destination = transferDestination()
        if (source.isBlank() || destination.isBlank()) {
            onFeedback("أدخل معرّف الحساب المصدر والوجهة.")
            return
        }
        request({
            val ids = JSONArray()
            transferIds().split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { ids.put(it) }
            catalogHttp(
                "/rest/v1/rpc/commercial_catalog_send_transfer",
                JSONObject()
                    .put("p_source_organization_id", source.trim())
                    .put("p_destination_organization_id", destination.trim())
                    .put("p_global_item_ids", if (ids.length() == 0) JSONObject.NULL else ids),
                active
            )
        }, { onFeedback("اكتملت عملية نقل روابط الدليل: $it") })
    }

    fun loadPendingTransfers() {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة المستقبلة في الحقل العلوي أولًا.")
            return
        }
        request({
            catalogGet(
                "/rest/v1/commercial_catalog_transfers?select=id,source_organization_id,destination_organization_id,item_count,status,created_at&destination_organization_id=eq.$orgId&status=eq.pending&order=created_at.desc",
                active
            )
        }, { raw ->
            val transfers = parsePendingCatalogTransfers(raw)
            onPendingTransfers(transfers)
            onFeedback("طلبات النقل الواردة: ${transfers.size}")
        })
    }

    fun respondToTransfer(id: String, accept: Boolean) {
        val active = accessToken() ?: return
        request({
            catalogHttp(
                "/rest/v1/rpc/${if (accept) "commercial_catalog_accept_transfer" else "commercial_catalog_reject_transfer"}",
                JSONObject().put("p_transfer_id", id),
                active
            )
        }, {
            onFeedback(if (accept) "تم قبول طلب النقل: $it" else "تم رفض طلب النقل: $it")
            loadPendingTransfers()
        })
    }
}
