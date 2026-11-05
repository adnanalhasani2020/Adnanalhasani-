package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

internal data class CommercialInventoryStocktake(
    val id: String,
    val status: String,
    val notes: String,
    val createdAt: String
)

internal data class CommercialInventoryStocktakeLine(
    val id: String,
    val offeringId: String,
    val productName: String,
    val unitName: String,
    val expectedQuantity: BigDecimal,
    val countedQuantity: String,
    val adjustmentUnitCostMinor: String
)

private data class InventoryStocktakeContext(
    val organizationId: String,
    val activityId: String,
    val locationId: String,
    val accessToken: String
)

private fun inventoryStocktakeContext(context: Context): InventoryStocktakeContext {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val organizationId = saved.getString("organization_id", null).orEmpty()
    val activityId = saved.getString("activity_id", null).orEmpty()
    val locationId = saved.getString("location_id", null).orEmpty()
    require(organizationId.isNotBlank() && activityId.isNotBlank() && locationId.isNotBlank()) {
        "بيانات المؤسسة والنشاط والفرع غير مكتملة."
    }
    val (_, access) = commercialMasterDataAccess(context)
    return InventoryStocktakeContext(organizationId, activityId, locationId, access)
}

private fun stocktakeActionKey(context: Context, stocktakeId: String, action: String): String {
    require(stocktakeId.isNotBlank() && action in setOf("post", "reverse"))
    val prefs = context.getSharedPreferences("commercial_inventory_stocktake_keys_v1", Context.MODE_PRIVATE)
    val key = "stocktake:$stocktakeId:$action"
    return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString(key, it).commit()) {
                "تعذر حفظ مفتاح العملية؛ لم يتم إرسال طلب الترحيل أو العكس."
            }
        }
}

private fun stocktakeCreateKey(context: Context, captured: InventoryStocktakeContext): String {
    val prefs = context.getSharedPreferences("commercial_inventory_stocktake_keys_v1", Context.MODE_PRIVATE)
    val saved = prefs.getString("pending_create", null)
    if (!saved.isNullOrBlank()) {
        val pending = runCatching { JSONObject(saved) }.getOrElse {
            throw IllegalStateException("تعذر قراءة طلب إنشاء الجرد المعلّق. لم نرسل طلبًا جديدًا حفاظًا على عدم التكرار.")
        }
        require(
            pending.optString("organization_id") == captured.organizationId &&
                pending.optString("activity_id") == captured.activityId &&
                pending.optString("location_id") == captured.locationId
        ) {
            "يوجد طلب إنشاء جرد معلّق لفرع أو نشاط آخر. ارجع إلى سياقه الأصلي لإكماله قبل إنشاء جرد جديد."
        }
        return pending.optString("idempotency_key").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("مفتاح إنشاء الجرد المعلّق غير صالح؛ لم نرسل طلبًا جديدًا.")
    }
    val key = UUID.randomUUID().toString()
    val pending = JSONObject()
        .put("organization_id", captured.organizationId)
        .put("activity_id", captured.activityId)
        .put("location_id", captured.locationId)
        .put("idempotency_key", key)
    check(prefs.edit().putString("pending_create", pending.toString()).commit()) {
        "تعذر حفظ مفتاح إنشاء الجرد؛ لم يتم إرسال الطلب."
    }
    return key
}

private fun stocktakeRpc(
    context: Context,
    captured: InventoryStocktakeContext,
    function: String,
    payload: JSONObject
): JSONObject {
    // Re-read the selected context immediately before the network write. A user
    // switch between validation and send must never redirect an old stocktake ID.
    val current = inventoryStocktakeContext(context)
    require(
        current.organizationId == captured.organizationId &&
            current.activityId == captured.activityId &&
            current.locationId == captured.locationId
    ) {
        "تغيرت المؤسسة أو النشاط أو الفرع أثناء تجهيز الطلب. لم نرسل أي عملية؛ أعد فتح مستند الجرد."
    }
    val raw = SupabaseClient.request("/rest/v1/rpc/$function", "POST", payload, current.accessToken)
    return JSONObject(raw)
}

private fun requireStocktakeMatchesCurrentContext(context: Context, stocktakeId: String): InventoryStocktakeContext {
    val current = inventoryStocktakeContext(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_inventory_stocktakes" +
            "?select=id&organization_id=eq.${current.organizationId}" +
            "&activity_id=eq.${current.activityId}&location_id=eq.${current.locationId}" +
            "&id=eq.$stocktakeId&limit=1",
        current.accessToken
    )
    require(rows.length() == 1) {
        "تغير الفرع أو النشاط المحدد، أو أن مستند الجرد لا يتبع السياق الحالي. لم تُرسل أي عملية."
    }
    return current
}

internal fun loadCommercialInventoryStocktakes(context: Context): List<CommercialInventoryStocktake> {
    val current = inventoryStocktakeContext(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_inventory_stocktakes" +
            "?select=id,status,notes,created_at" +
            "&organization_id=eq.${current.organizationId}" +
            "&activity_id=eq.${current.activityId}&location_id=eq.${current.locationId}" +
            "&order=created_at.desc,id.desc&limit=50",
        current.accessToken
    )
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val status = row.optString("status")
            require(id.isNotBlank() && status in setOf("draft", "submitted", "approved", "posted", "reversed")) {
                "بيانات مستند جرد غير مكتملة؛ تم إيقاف عرض القائمة."
            }
            add(CommercialInventoryStocktake(
                id = id,
                status = status,
                notes = row.optString("notes"),
                createdAt = row.optString("created_at")
            ))
        }
    }
}

internal fun loadCommercialInventoryStocktakeLines(
    context: Context,
    stocktakeId: String
): List<CommercialInventoryStocktakeLine> {
    val current = requireStocktakeMatchesCurrentContext(context, stocktakeId)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_inventory_stocktake_lines" +
            "?select=id,offering_id,expected_quantity,counted_quantity,adjustment_unit_cost_minor," +
            "offering:commercial_offerings!inner(id,product:commercial_products!inner(name,unit_name))" +
            "&stocktake_id=eq.$stocktakeId&organization_id=eq.${current.organizationId}" +
            "&location_id=eq.${current.locationId}&order=offering_id.asc",
        current.accessToken
    )
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val offering = row.optJSONObject("offering")
                ?: throw IllegalStateException("يوجد بند جرد بلا صنف مرتبط.")
            val product = offering.optJSONObject("product")
                ?: throw IllegalStateException("تعذر قراءة بيانات صنف في الجرد.")
            val expected = runCatching { BigDecimal(row.getString("expected_quantity")) }
                .getOrElse { throw IllegalStateException("كمية الرصيد الدفتري غير صالحة؛ تم إيقاف الجرد.") }
            require(expected.signum() >= 0 && expected.stripTrailingZeros().scale() <= 6) {
                "كمية الرصيد الدفتري خارج الدقة المعتمدة."
            }
            val counted = row.optString("counted_quantity").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val cost = row.optString("adjustment_unit_cost_minor").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            add(CommercialInventoryStocktakeLine(
                id = row.optString("id"),
                offeringId = row.optString("offering_id"),
                productName = product.optString("name").ifBlank { "صنف بلا اسم" },
                unitName = product.optString("unit_name").ifBlank { "وحدة" },
                expectedQuantity = expected,
                countedQuantity = counted,
                adjustmentUnitCostMinor = cost
            ))
        }
    }
}

internal fun createCommercialInventoryStocktake(context: Context, notes: String): String {
    require(notes.length <= 2000) { "الملاحظات أطول من الحد المسموح." }
    val current = inventoryStocktakeContext(context)
    val key = stocktakeCreateKey(context, current)
    val result = stocktakeRpc(context, current, "commercial_create_inventory_stocktake", JSONObject()
        .put("p_organization_id", current.organizationId)
        .put("p_activity_id", current.activityId)
        .put("p_location_id", current.locationId)
        .put("p_idempotency_key", key)
        .put("p_notes", notes.trim()))
    val id = result.optString("stocktake_id")
    require(id.isNotBlank() && id != "null") { "لم يُرجع الخادم معرف مستند الجرد." }
    val cleared = context.getSharedPreferences("commercial_inventory_stocktake_keys_v1", Context.MODE_PRIVATE)
        .edit().remove("pending_create").commit()
    check(cleared) {
        "أنشأ الخادم مسودة الجرد، لكن تعذر مسح مفتاح إعادة المحاولة المحلي. أعد المحاولة بنفس الطلب ولا تنشئ مستندًا جديدًا الآن."
    }
    return id
}

internal fun saveCommercialInventoryStocktakeCounts(
    context: Context,
    stocktakeId: String,
    lines: List<CommercialInventoryStocktakeLine>
): Int {
    val captured = requireStocktakeMatchesCurrentContext(context, stocktakeId)
    val changed = lines.filter { it.countedQuantity.isNotBlank() }
    require(changed.isNotEmpty()) { "أدخل كمية فعلية لبند واحد على الأقل قبل الحفظ." }
    val payload = JSONArray()
    for (line in changed) {
        val rawQuantity = line.countedQuantity.trim()
        require(rawQuantity.matches(Regex("^[0-9]{1,14}([.][0-9]{1,6})?$"))) {
            "الكمية الفعلية للصنف ${line.productName} غير صالحة."
        }
        val quantity = BigDecimal(rawQuantity)
        require(quantity.signum() >= 0 && quantity.stripTrailingZeros().scale() <= 6) {
            "الكمية الفعلية للصنف ${line.productName} خارج الدقة المعتمدة."
        }
        val rawCost = line.adjustmentUnitCostMinor.trim()
        if (rawCost.isNotBlank()) require(rawCost.matches(Regex("^[0-9]{1,19}$"))) {
            "تكلفة الزيادة للصنف ${line.productName} غير صالحة."
        }
        payload.put(JSONObject()
            .put("line_id", line.id)
            .put("counted_quantity", quantity.toPlainString())
            .put("adjustment_unit_cost_minor", if (rawCost.isBlank()) JSONObject.NULL else rawCost))
    }
    val result = stocktakeRpc(context, captured, "commercial_set_inventory_stocktake_counts", JSONObject()
        .put("p_stocktake_id", stocktakeId)
        .put("p_lines", payload))
    return result.optInt("updated_line_count", changed.size)
}

internal fun submitCommercialInventoryStocktake(context: Context, stocktakeId: String) {
    val captured = requireStocktakeMatchesCurrentContext(context, stocktakeId)
    stocktakeRpc(context, captured, "commercial_submit_inventory_stocktake", JSONObject().put("p_stocktake_id", stocktakeId))
}

internal fun approveCommercialInventoryStocktake(context: Context, stocktakeId: String) {
    val captured = requireStocktakeMatchesCurrentContext(context, stocktakeId)
    stocktakeRpc(context, captured, "commercial_approve_inventory_stocktake", JSONObject().put("p_stocktake_id", stocktakeId))
}

internal fun postCommercialInventoryStocktake(context: Context, stocktakeId: String) {
    val captured = requireStocktakeMatchesCurrentContext(context, stocktakeId)
    stocktakeRpc(context, captured, "commercial_post_inventory_stocktake", JSONObject()
        .put("p_stocktake_id", stocktakeId)
        .put("p_idempotency_key", stocktakeActionKey(context, stocktakeId, "post")))
}

internal fun reverseCommercialInventoryStocktake(context: Context, stocktakeId: String, reason: String) {
    val captured = requireStocktakeMatchesCurrentContext(context, stocktakeId)
    require(reason.trim().length >= 8) { "اكتب سببًا واضحًا للعكس (8 أحرف على الأقل)." }
    stocktakeRpc(context, captured, "commercial_reverse_inventory_stocktake", JSONObject()
        .put("p_stocktake_id", stocktakeId)
        .put("p_reason", reason.trim())
        .put("p_idempotency_key", stocktakeActionKey(context, stocktakeId, "reverse")))
}
