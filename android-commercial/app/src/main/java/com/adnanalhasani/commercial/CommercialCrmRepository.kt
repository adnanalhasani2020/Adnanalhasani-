package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CommercialCrmLead(
    val id: String,
    val name: String,
    val companyName: String,
    val email: String,
    val phone: String,
    val source: String,
    val status: String,
    val assignedTo: String,
    val notes: String,
    val nextFollowUpAt: String,
    val version: Int,
    val createdAt: String
)

internal data class CommercialCrmActivity(
    val id: String,
    val leadId: String,
    val activityType: String,
    val title: String,
    val description: String,
    val dueAt: String,
    val status: String,
    val assignedTo: String,
    val version: Int,
    val completedAt: String
)

private fun crmContext(context: Context): Triple<String, String, String> {
    val (organizationId, access) = commercialMasterDataAccess(context)
    val activityId = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
        .getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    return Triple(organizationId, activityId, access)
}

internal fun loadCommercialCrmLeads(context: Context): List<CommercialCrmLead> {
    val (organizationId, activityId, access) = crmContext(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_lead_list",
        "POST",
        JSONObject().put("p_organization_id", organizationId)
            .put("p_activity_id", activityId)
            .put("p_limit", 200),
        access
    ))
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id").takeUnless { it == "null" }.orEmpty()
            val name = row.optString("name").trim()
            if (id.isBlank() || name.isBlank()) continue
            add(CommercialCrmLead(
                id = id,
                name = name,
                companyName = row.optString("company_name"),
                email = row.optString("email"),
                phone = row.optString("phone"),
                source = row.optString("source"),
                status = row.optString("status"),
                assignedTo = row.optString("assigned_to"),
                notes = row.optString("notes"),
                nextFollowUpAt = row.optString("next_follow_up_at").takeUnless { it == "null" }.orEmpty(),
                version = row.optInt("version", 1),
                createdAt = row.optString("created_at")
            ))
        }
    }
}

internal fun createCommercialCrmLead(
    context: Context,
    name: String,
    companyName: String,
    email: String,
    phone: String,
    source: String,
    notes: String,
    nextFollowUpAt: String?,
    idempotencyKey: String
): JSONObject {
    require(name.isNotBlank() && idempotencyKey.length in 8..200)
    val (organizationId, activityId, access) = crmContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_lead_create",
        "POST",
        JSONObject().put("p_organization_id", organizationId)
            .put("p_activity_id", activityId)
            .put("p_name", name.trim())
            .put("p_company_name", companyName.trim())
            .put("p_email", email.trim())
            .put("p_phone", phone.trim())
            .put("p_source", source)
            .put("p_notes", notes)
            .put("p_assigned_to", JSONObject.NULL)
            .put("p_next_follow_up_at", nextFollowUpAt ?: JSONObject.NULL)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
}

internal fun updateCommercialCrmLead(
    context: Context,
    lead: CommercialCrmLead,
    status: String,
    notes: String,
    nextFollowUpAt: String?
): JSONObject {
    val (_, _, access) = crmContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_lead_update",
        "POST",
        JSONObject().put("p_lead_id", lead.id)
            .put("p_expected_version", lead.version)
            .put("p_status", status)
            .put("p_notes", notes)
            .put("p_next_follow_up_at", nextFollowUpAt ?: JSONObject.NULL)
            .put("p_assigned_to", JSONObject.NULL),
        access
    ))
}

internal fun loadCommercialCrmActivities(context: Context): List<CommercialCrmActivity> {
    val (organizationId, activityId, access) = crmContext(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_activity_list",
        "POST",
        JSONObject().put("p_organization_id", organizationId)
            .put("p_activity_id", activityId)
            .put("p_limit", 200),
        access
    ))
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id").takeUnless { it == "null" }.orEmpty()
            if (id.isBlank()) continue
            add(CommercialCrmActivity(
                id = id,
                leadId = row.optString("lead_id"),
                activityType = row.optString("activity_type"),
                title = row.optString("title"),
                description = row.optString("description"),
                dueAt = row.optString("due_at").takeUnless { it == "null" }.orEmpty(),
                status = row.optString("status"),
                assignedTo = row.optString("assigned_to"),
                version = row.optInt("version", 1),
                completedAt = row.optString("completed_at").takeUnless { it == "null" }.orEmpty()
            ))
        }
    }
}

internal fun createCommercialCrmActivity(
    context: Context,
    leadId: String,
    activityType: String,
    title: String,
    description: String,
    dueAt: String?,
    idempotencyKey: String
): JSONObject {
    require(leadId.isNotBlank() && title.isNotBlank() && idempotencyKey.length in 8..200)
    val (organizationId, activityId, access) = crmContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_activity_create",
        "POST",
        JSONObject().put("p_organization_id", organizationId)
            .put("p_activity_id", activityId)
            .put("p_lead_id", leadId)
            .put("p_activity_type", activityType)
            .put("p_title", title.trim())
            .put("p_description", description)
            .put("p_due_at", dueAt ?: JSONObject.NULL)
            .put("p_assigned_to", JSONObject.NULL)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
}

internal fun updateCommercialCrmActivity(
    context: Context,
    activity: CommercialCrmActivity,
    status: String,
    description: String
): JSONObject {
    val (_, _, access) = crmContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_activity_update",
        "POST",
        JSONObject().put("p_activity_record_id", activity.id)
            .put("p_expected_version", activity.version)
            .put("p_status", status)
            .put("p_description", description),
        access
    ))
}

internal fun convertCommercialCrmLead(
    context: Context,
    lead: CommercialCrmLead,
    idempotencyKey: String
): JSONObject {
    require(lead.id.isNotBlank() && idempotencyKey.length in 8..200)
    val (_, _, access) = crmContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_lead_convert",
        "POST",
        JSONObject()
            .put("p_lead_id", lead.id)
            .put("p_expected_version", lead.version)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
}

internal fun newCommercialCrmIdempotencyKey(): String = UUID.randomUUID().toString()


internal data class CommercialCrmOpportunity(
    val id: String,
    val leadId: String,
    val leadName: String,
    val title: String,
    val stage: String,
    val probability: Int,
    val expectedValueMinor: Long,
    val currencyCode: String,
    val expectedCloseDate: String,
    val assignedTo: String,
    val notes: String,
    val lostReason: String,
    val version: Int
)

internal fun loadCommercialCrmOpportunities(context: Context, stage: String = ""): List<CommercialCrmOpportunity> {
    val (organizationId, activityId, access) = crmContext(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_opportunity_list", "POST",
        JSONObject().put("p_organization_id", organizationId).put("p_activity_id", activityId)
            .put("p_stage", stage.takeIf { it.isNotBlank() } ?: JSONObject.NULL).put("p_limit", 200),
        access
    ))
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val leadId = row.optString("lead_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val title = row.optString("title").trim()
            if (id.isBlank() || leadId.isBlank() || title.isBlank()) continue
            add(CommercialCrmOpportunity(
                id = id, leadId = leadId, leadName = row.optString("lead_name"),
                title = title, stage = row.optString("stage"), probability = row.optInt("probability"),
                expectedValueMinor = row.optLong("expected_value_minor"),
                currencyCode = row.optString("currency_code", "YER"),
                expectedCloseDate = row.optString("expected_close_date").takeUnless { it == "null" }.orEmpty(),
                assignedTo = row.optString("assigned_to"), notes = row.optString("notes"),
                lostReason = row.optString("lost_reason"), version = row.optInt("version", 1)
            ))
        }
    }
}

internal fun createCommercialCrmOpportunity(
    context: Context, leadId: String, title: String, stage: String, probability: Int,
    expectedValueMinor: Long, expectedCloseDate: String?, notes: String, idempotencyKey: String
): JSONObject {
    require(leadId.isNotBlank() && title.isNotBlank() && probability in 0..100 && expectedValueMinor >= 0)
    val (organizationId, activityId, access) = crmContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_opportunity_create", "POST",
        JSONObject().put("p_organization_id", organizationId).put("p_activity_id", activityId)
            .put("p_lead_id", leadId).put("p_title", title.trim()).put("p_stage", stage)
            .put("p_probability", probability).put("p_expected_value_minor", expectedValueMinor)
            .put("p_currency_code", JSONObject.NULL)
            .put("p_expected_close_date", expectedCloseDate?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("p_notes", notes.take(4000)).put("p_assigned_to", JSONObject.NULL)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
}

internal fun updateCommercialCrmOpportunity(
    context: Context, opportunity: CommercialCrmOpportunity, stage: String,
    probability: Int, expectedValueMinor: Long, expectedCloseDate: String?,
    notes: String, lostReason: String
): JSONObject {
    val (_, _, access) = crmContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_crm_opportunity_update", "POST",
        JSONObject().put("p_opportunity_id", opportunity.id)
            .put("p_expected_version", opportunity.version).put("p_stage", stage)
            .put("p_probability", probability).put("p_expected_value_minor", expectedValueMinor)
            .put("p_expected_close_date", expectedCloseDate?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("p_notes", notes.take(4000)).put("p_lost_reason", lostReason.take(1000)),
        access
    ))
}
