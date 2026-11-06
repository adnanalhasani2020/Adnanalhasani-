package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CommercialCrmLead(
    val id: String,
    val name: String,
    val company: String,
    val phone: String,
    val email: String,
    val source: String,
    val stage: String,
    val expectedValueMinor: Long,
    val currencyCode: String,
    val nextFollowUpAt: String,
    val notes: String,
    val lostReason: String,
    val assignedTo: String,
    val createdBy: String,
    val version: Int,
    val updatedAt: String
)

internal data class CommercialCrmActivity(
    val id: String,
    val type: String,
    val subject: String,
    val body: String,
    val dueAt: String,
    val completedAt: String,
    val createdAt: String
)

private fun crmContext(context: Context): Pair<String, String> = commercialMasterDataAccess(context)

internal fun loadCommercialCrmLeads(context: Context, stage: String = ""): List<CommercialCrmLead> {
    val (orgId, token) = crmContext(context)
    val payload = JSONObject().put("p_organization_id", orgId)
        .put("p_stage", stage.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("p_limit", 200)
    val rows = JSONArray(SupabaseClient.request("/rest/v1/rpc/commercial_crm_list_leads", "POST", payload, token))
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val name = row.optString("lead_name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank() || name.isBlank()) continue
            add(CommercialCrmLead(
                id = id, name = name, company = row.optString("company_name"),
                phone = row.optString("phone"), email = row.optString("email"),
                source = row.optString("source"), stage = row.optString("stage"),
                expectedValueMinor = row.optLong("expected_value_minor"),
                currencyCode = row.optString("currency_code", "YER"),
                nextFollowUpAt = row.optString("next_follow_up_at").takeUnless { it == "null" }.orEmpty(),
                notes = row.optString("notes"), lostReason = row.optString("lost_reason"),
                assignedTo = row.optString("assigned_to"), createdBy = row.optString("created_by"),
                version = row.optInt("version", 1), updatedAt = row.optString("updated_at")
            ))
        }
    }
}

internal fun createCommercialCrmLead(
    context: Context, name: String, company: String, phone: String, email: String,
    source: String, expectedValueMinor: Long, notes: String, idempotencyKey: String
): JSONObject {
    require(name.isNotBlank() && name.length <= 180)
    require(company.length <= 180 && phone.length <= 40 && email.length <= 254)
    require(expectedValueMinor >= 0 && notes.length <= 4000)
    val (orgId, token) = crmContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_create_lead", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_activity_id", JSONObject.NULL)
            .put("p_lead_name", name.trim()).put("p_company_name", company.trim())
            .put("p_phone", phone.trim()).put("p_email", email.trim())
            .put("p_source", source).put("p_expected_value_minor", expectedValueMinor)
            .put("p_currency_code", JSONObject.NULL).put("p_next_follow_up_at", JSONObject.NULL)
            .put("p_notes", notes.trim()).put("p_assigned_to", JSONObject.NULL)
            .put("p_idempotency_key", idempotencyKey),
        token
    ))
}

internal fun updateCommercialCrmLead(
    context: Context, lead: CommercialCrmLead, stage: String, notes: String,
    nextFollowUpAt: String, lostReason: String
): JSONObject {
    val (orgId, token) = crmContext(context)
    val followUp = nextFollowUpAt.trim().takeIf { it.isNotBlank() }?.let { it } ?: JSONObject.NULL
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_update_lead", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_lead_id", lead.id)
            .put("p_stage", stage).put("p_notes", notes.take(4000))
            .put("p_next_follow_up_at", followUp).put("p_lost_reason", lostReason.take(1000))
            .put("p_expected_value_minor", lead.expectedValueMinor).put("p_version", lead.version),
        token
    ))
}

internal fun addCommercialCrmActivity(
    context: Context, leadId: String, type: String, subject: String, body: String,
    dueAt: String, idempotencyKey: String
): JSONObject {
    require(leadId.isNotBlank() && subject.isNotBlank() && subject.length <= 180 && body.length <= 4000)
    val (orgId, token) = crmContext(context)
    val due = dueAt.trim().takeIf { it.isNotBlank() }?.let { it } ?: JSONObject.NULL
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_add_activity", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_lead_id", leadId)
            .put("p_activity_type", type).put("p_subject", subject.trim()).put("p_body", body.trim())
            .put("p_due_at", due).put("p_assigned_to", JSONObject.NULL)
            .put("p_idempotency_key", idempotencyKey),
        token
    ))
}

internal fun loadCommercialCrmActivities(context: Context, leadId: String): List<CommercialCrmActivity> {
    val (orgId, token) = crmContext(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_list_activities", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_lead_id", leadId).put("p_limit", 100),
        token
    ))
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank()) continue
            add(CommercialCrmActivity(
                id, row.optString("activity_type"), row.optString("subject"),
                row.optString("body"), row.optString("due_at").takeUnless { it == "null" }.orEmpty(),
                row.optString("completed_at").takeUnless { it == "null" }.orEmpty(),
                row.optString("created_at")
            ))
        }
    }
}

internal fun newCommercialCrmIdempotencyKey(prefix: String): String =
    "crm-$prefix-" + UUID.randomUUID().toString()
