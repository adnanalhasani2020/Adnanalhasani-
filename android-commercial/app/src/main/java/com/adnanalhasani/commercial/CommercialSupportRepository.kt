package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CommercialSupportTicket(
    val id:String,val ticketNo:Long,val customerId:String,val customerName:String,
    val subject:String,val description:String,val category:String,val priority:String,
    val status:String,val assignedTo:String,val createdBy:String,val version:Int,
    val createdAt:String
)

internal data class CommercialSupportMember(val userId:String,val role:String){
    val label:String get()="$role • ${userId.take(8)}"
}

internal data class CommercialSupportMessage(
    val id:String,val body:String,val createdBy:String,val createdAt:String
)

private fun supportContext(context:Context):Triple<String,String,String>{
    val (organizationId,access)=commercialMasterDataAccess(context)
    val activityId=context.getSharedPreferences("commercial_cloud_context_v1",Context.MODE_PRIVATE)
        .getString("activity_id",null).orEmpty()
    require(activityId.isNotBlank()){ "ملف النشاط غير محفوظ." }
    return Triple(organizationId,activityId,access)
}

internal fun loadCommercialSupportMembers(context:Context):List<CommercialSupportMember>{
    val (organizationId,_,access)=supportContext(context)
    val rows=commercialCloudPagedGet(
        "/rest/v1/commercial_organization_members?select=user_id,role&organization_id=eq.$organizationId&state=eq.active&order=role.asc,user_id.asc",
        access
    )
    return buildList{
        for(i in 0 until rows.length()){
            val row=rows.optJSONObject(i)?:continue
            val id=row.optString("user_id").takeUnless{it.isBlank()||it=="null"}.orEmpty()
            if(id.isNotBlank())add(CommercialSupportMember(id,row.optString("role")))
        }
    }
}

internal fun loadCommercialSupportRole(context:Context):String{
    val (organizationId,activityId,access)=supportContext(context)
    return SupabaseClient.request(
        "/rest/v1/rpc/commercial_support_my_role","POST",
        JSONObject().put("p_organization_id",organizationId).put("p_activity_id",activityId),access
    ).trim().trim('"')
}

internal fun loadCommercialSupportTickets(context:Context,status:String=""):List<CommercialSupportTicket>{
    val (organizationId,activityId,access)=supportContext(context)
    val rows=JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_support_ticket_list","POST",
        JSONObject().put("p_organization_id",organizationId).put("p_activity_id",activityId)
            .put("p_status",status.takeIf{it.isNotBlank()}?:JSONObject.NULL).put("p_limit",200),
        access
    ))
    return buildList{
        for(i in 0 until rows.length()){
            val row=rows.optJSONObject(i)?:continue
            val id=row.optString("id").takeUnless{it.isBlank()||it=="null"}.orEmpty()
            if(id.isBlank())continue
            add(CommercialSupportTicket(
                id=id,ticketNo=row.optLong("ticket_no"),customerId=row.optString("customer_id").takeUnless{it=="null"}.orEmpty(),
                customerName=row.optString("customer_name").takeUnless{it=="null"}.orEmpty(),
                subject=row.optString("subject"),description=row.optString("description"),
                category=row.optString("category"),priority=row.optString("priority"),status=row.optString("status"),
                assignedTo=row.optString("assigned_to").takeUnless{it=="null"}.orEmpty(),
                createdBy=row.optString("created_by").takeUnless{it=="null"}.orEmpty(),
                version=row.optInt("version",1),createdAt=row.optString("created_at")
            ))
        }
    }
}

internal fun createCommercialSupportTicket(
    context:Context,customerId:String?,subject:String,description:String,category:String,
    priority:String,assignedTo:String?,idempotencyKey:String
):JSONObject{
    require(subject.isNotBlank()&&description.isNotBlank()&&idempotencyKey.length in 8..200)
    val (organizationId,activityId,access)=supportContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_support_ticket_create","POST",
        JSONObject().put("p_organization_id",organizationId).put("p_activity_id",activityId)
            .put("p_customer_id",customerId?:JSONObject.NULL).put("p_subject",subject.trim())
            .put("p_description",description.trim()).put("p_category",category).put("p_priority",priority)
            .put("p_assigned_to",assignedTo?:JSONObject.NULL).put("p_source","internal")
            .put("p_idempotency_key",idempotencyKey),
        access
    ))
}

internal fun updateCommercialSupportTicket(
    context:Context,ticket:CommercialSupportTicket,status:String,priority:String,assignedTo:String?
):JSONObject{
    val (_,_,access)=supportContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_support_ticket_update","POST",
        JSONObject().put("p_ticket_id",ticket.id).put("p_expected_version",ticket.version)
            .put("p_status",status).put("p_priority",priority)
            .put("p_assigned_to",assignedTo?:ticket.assignedTo.takeIf{it.isNotBlank()}?:JSONObject.NULL),
        access
    ))
}

internal fun loadCommercialSupportMessages(context:Context,ticketId:String):List<CommercialSupportMessage>{
    val (_,_,access)=supportContext(context)
    val rows=JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_support_ticket_message_list","POST",
        JSONObject().put("p_ticket_id",ticketId).put("p_limit",200),access
    ))
    return buildList{
        for(i in 0 until rows.length()){
            val row=rows.optJSONObject(i)?:continue
            val id=row.optString("id").takeUnless{it.isBlank()||it=="null"}.orEmpty()
            if(id.isNotBlank())add(CommercialSupportMessage(id,row.optString("body"),
                row.optString("created_by").takeUnless{it=="null"}.orEmpty(),row.optString("created_at")))
        }
    }
}

internal fun addCommercialSupportMessage(
    context:Context,ticketId:String,body:String,idempotencyKey:String
):JSONObject{
    require(ticketId.isNotBlank()&&body.isNotBlank()&&idempotencyKey.length in 8..200)
    val (_,_,access)=supportContext(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_support_ticket_message_create","POST",
        JSONObject().put("p_ticket_id",ticketId).put("p_body",body.trim())
            .put("p_idempotency_key",idempotencyKey),access
    ))
}

internal fun newCommercialSupportIdempotencyKey():String="support-"+UUID.randomUUID().toString()
