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

internal fun newCommercialCrmIdempotencyKey(): String = UUID.randomUUID().toString()
