package com.adnanalhasani.commercial

import org.json.JSONObject

/** Coordinates invoice request submission, review, and trusted automatic item matching. */
internal class CatalogInvoiceWorkflow(
    private val state: CatalogInvoiceTransferState,
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val marketBranches: () -> List<CatalogBranchOption>,
    private val selectedBranchId: () -> String,
    private val request: (
        action: () -> String,
        onSuccess: (String) -> Unit,
        onFailure: ((Throwable) -> Unit)?
    ) -> Unit,
    private val onFeedback: (String) -> Unit,
    private val onRequestsLoaded: (List<InvoicePurchaseRequestRow>) -> Unit
) {
    fun loadInvoiceRequests() {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة UUID أولًا.")
            return
        }
        request({
            catalogGet(
                "/rest/v1/commercial_invoice_purchase_requests?select=id,source_organization_id,destination_organization_id,source_invoice_number,currency_code,total_minor,status,destination_purchase_order_id,created_at&or=(source_organization_id.eq." +
                    orgId + ",destination_organization_id.eq." + orgId + ")&order=created_at.desc&limit=50",
                active
            )
        }, { raw ->
            val requests = parseInvoicePurchaseRequests(raw)
            onRequestsLoaded(requests)
            onFeedback("طلبات تحويل الفواتير: " + requests.size)
        }, null)
    }

    fun requestInvoiceAsPurchase() {
        val active = accessToken() ?: return
        val source = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة البائعة UUID أولًا.")
            return
        }
        if (state.invoiceSaleId.isBlank() || state.invoiceDestinationOrg.isBlank()) {
            onFeedback("أدخل معرّف فاتورة البيع ومعرّف المؤسسة المستقبلة.")
            return
        }
        if (source == state.invoiceDestinationOrg.trim()) {
            onFeedback("لا يمكن تحويل الفاتورة إلى المؤسسة نفسها.")
            return
        }
        val key = state.invoiceRequestKey
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_request_invoice_as_purchase",
                JSONObject()
                    .put("p_source_organization_id", source)
                    .put("p_destination_organization_id", state.invoiceDestinationOrg.trim())
                    .put("p_sale_id", state.invoiceSaleId.trim())
                    .put("p_request_key", key),
                active
            )
        }, { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            onFeedback(
                "تم إرسال الطلب: " + (result?.optString("state") ?: raw) +
                    ". لن يتغير مخزون المؤسسة المستقبلة قبل الاستلام."
            )
            state.invoiceRequestKey = java.util.UUID.randomUUID().toString()
            loadInvoiceRequests()
        }, null)
    }

    fun reviewInvoicePurchaseRequest(id: String, approve: Boolean) {
        val active = accessToken() ?: return
        val receiveLocation = state.invoiceReceiveLocationId.ifBlank { selectedBranchId() }
        if (approve && (receiveLocation.isBlank() || marketBranches().none { it.id == receiveLocation })) {
            onFeedback("اختر فرع استلام نشطًا تابعًا للمؤسسة المستقبلة قبل الموافقة.")
            return
        }
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_review_invoice_purchase_request",
                JSONObject()
                    .put("p_request_id", id)
                    .put("p_decision", if (approve) "approve" else "reject")
                    .put("p_review_note", JSONObject.NULL)
                    .put("p_receive_location_id", if (approve) receiveLocation else JSONObject.NULL),
                active
            )
        }, {
            onFeedback(
                if (approve) "تمت الموافقة وإنشاء أمر شراء مسودة في الفرع المحدد. الاستلام منفصل."
                else "تم رفض طلب تحويل الفاتورة."
            )
            loadInvoiceRequests()
        }, null)
    }

    fun mapKnownInvoicePurchaseItems(id: String) {
        val active = accessToken() ?: return
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_invoice_purchase_map_known_items",
                JSONObject().put("p_request_id", id),
                active
            )
        }, { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            onFeedback(
                "تم ربط " + (result?.optInt("mapped_lines", 0) ?: 0) +
                    " صنفًا مطابقًا بشكل موثوق. الأصناف غير المطابقة: " +
                    (result?.optInt("unmapped_lines", 0) ?: 0) + ". لم يتغير المخزون."
            )
            loadInvoiceRequests()
        }, null)
    }
}
